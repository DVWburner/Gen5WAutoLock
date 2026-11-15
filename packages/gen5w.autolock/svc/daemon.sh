#!/system/bin/sh
export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib

BASE="/data/nemesis/autolock"
mkdir -p "$BASE"
chmod 777 "$BASE"

CFG_ENABLED="$BASE/enabled"
CFG_DELAY="$BASE/delay"
CFG_IGNORE_HATCH="$BASE/ignore_hatch"
DOOR_FILE="$BASE/door_state"
BT_CONN_FILE="$BASE/bt_connected"
WIFI_STATE_FILE="$BASE/wifi_state"
STATUS_FILE="$BASE/status"
LOCAL_LOG="$BASE/autolock.log"

[ -f "$CFG_ENABLED" ] || echo "1" > "$CFG_ENABLED"
[ -f "$CFG_DELAY" ] || echo "10" > "$CFG_DELAY"
[ -f "$CFG_IGNORE_HATCH" ] || echo "1" > "$CFG_IGNORE_HATCH"
[ -f "$DOOR_FILE" ] || echo "0 1 0" > "$DOOR_FILE"
[ -f "$BT_CONN_FILE" ] || echo "0" > "$BT_CONN_FILE"
[ -f "$WIFI_STATE_FILE" ] || echo "0 0 none" > "$WIFI_STATE_FILE"
chmod 666 "$CFG_ENABLED" "$CFG_DELAY" "$CFG_IGNORE_HATCH" "$DOOR_FILE" "$BT_CONN_FILE" "$WIFI_STATE_FILE" 2>/dev/null

find_usb() {
    for cand in /storage/usb0 /storage/usb1 /storage/usb2 /mnt/media_rw/usb0 /mnt/media_rw/usb1 /mnt/media_rw/usb2; do
        if [ -d "$cand" ] && [ -w "$cand" ]; then
            if grep -q " $cand " /proc/mounts 2>/dev/null; then
                echo "$cand"
                return 0
            fi
        fi
    done
    return 1
}

log_msg() {
    ts=$(date "+%Y-%m-%d %H:%M:%S" 2>/dev/null)
    line="[$ts] $1"
    echo "$line" >> "$LOCAL_LOG"
    chmod 666 "$LOCAL_LOG" 2>/dev/null
    usb=$(find_usb)
    if [ -n "$usb" ] && [ -f "$usb/autolock.log" ]; then
        echo "$line" >> "$usb/autolock.log" 2>/dev/null
    fi
}

one_line() {
    out=""
    while IFS= read -r l; do
        if [ -n "$l" ]; then
            if [ -z "$out" ]; then
                out="$l"
            else
                out="$out | $l"
            fi
        fi
    done
    echo "$out"
}

get_wifi_arp_ip() {
    if [ ! -f /proc/net/arp ]; then
        return 1
    fi
    while read -r ip hwtype flags mac mask dev; do
        case "$ip" in
            IP*|[a-zA-Z]*) continue ;;
        esac
        case "$dev" in
            wlan*|p2p*)
                if [ "$flags" != "0x0" ] && [ -n "$mac" ] && [ "$mac" != "00:00:00:00:00:00" ]; then
                    echo "$ip"
                    return 0
                fi
                ;;
        esac
    done < /proc/net/arp
    return 1
}

extract_ping_ms() {
    while read -r line; do
        for tok in $line; do
            case "$tok" in
                time=*)
                    val="${tok#time=}"
                    val="${val%%.*}"
                    case "$val" in
                        [0-9]*)
                            echo "$val"
                            return 0
                            ;;
                    esac
                    ;;
            esac
        done
    done
    return 1
}

check_wifi_peer() {
    peer_ip=$(get_wifi_arp_ip)
    if [ -n "$peer_ip" ]; then
        pout=$(ping -c 1 -W 1 "$peer_ip" 2>&1)
        if [ $? -eq 0 ]; then
            ms=$(echo "$pout" | extract_ping_ms)
            [ -z "$ms" ] && ms=1
            echo "1 $ms $peer_ip"
            return 0
        fi
        echo "0 0 $peer_ip"
        return 0
    fi
    echo "0 0 none"
}

compute_door_flags() {
    # Reads "$1"=any_cabin, "$2"=all_cabin_closed, "$3"=trunk_open, "$4"=ignore_hatch
    any_c="$1"
    all_c="$2"
    trunk_o="$3"
    ign_h="$4"
    [ -z "$any_c" ] && any_c=0
    [ -z "$all_c" ] && all_c=1
    [ -z "$trunk_o" ] && trunk_o=0
    [ -z "$ign_h" ] && ign_h=1

    if [ "$ign_h" = "1" ]; then
        eff_any="$any_c"
        eff_closed="$all_c"
    else
        if [ "$any_c" = "1" ] || [ "$trunk_o" = "1" ]; then
            eff_any=1
        else
            eff_any=0
        fi
        if [ "$all_c" = "1" ] && [ "$trunk_o" = "0" ]; then
            eff_closed=1
        else
            eff_closed=0
        fi
    fi
}

# Initialize state
prev_ign="-1"
ign_off_since=0
door_opened_after_off=0
door_closed_since=0
wait_phone_logged=0
locked_this_cycle=0
session_phone="none"
wifi_fail_streak=0
last_ign_off_hb=0

log_msg "autolock-daemon started (v1.5.0)"

# Ensure Android bridge service is running so door_state & bt_connected stay updated
am startservice -n com.gen5w.autolock/.AutoLockBridgeService >/dev/null 2>&1 || true

while true; do
    now=$(date +%s 2>/dev/null)
    [ -z "$now" ] && now=0

    enabled=$(cat "$CFG_ENABLED" 2>/dev/null)
    delay=$(cat "$CFG_DELAY" 2>/dev/null)
    ignore_hatch=$(cat "$CFG_IGNORE_HATCH" 2>/dev/null)
    [ -z "$enabled" ] && enabled=1
    [ -z "$delay" ] && delay=10
    [ -z "$ignore_hatch" ] && ignore_hatch=1

    # Poll Wi-Fi Direct peer reachability & Bluetooth state
    wifi_res=$(check_wifi_peer)
    set -- $wifi_res
    wifi_conn="$1"
    wifi_ms="$2"
    wifi_ip="$3"
    echo "$wifi_conn $wifi_ms $wifi_ip" > "$WIFI_STATE_FILE"
    chmod 666 "$WIFI_STATE_FILE" 2>/dev/null

    if [ "$wifi_conn" = "1" ]; then
        wifi_fail_streak=0
    else
        wifi_fail_streak=$((wifi_fail_streak + 1))
    fi

    bt_conn=$(cat "$BT_CONN_FILE" 2>/dev/null)
    [ -z "$bt_conn" ] && bt_conn=0

    # Read IGN status (1 = ON, 0 = OFF)
    ign_raw=$(service call com.hkmc.micom.bcm 17 2>&1)
    if echo "$ign_raw" | grep -q "00000001"; then
        ign=1
    else
        ign=0
    fi

    # Update active session phone type while IGN is ON or while waiting for exit
    if [ "$ign" = "1" ]; then
        if [ "$wifi_conn" = "1" ]; then
            session_phone="wifi"
        elif [ "$session_phone" != "wifi" ] && [ "$bt_conn" = "1" ]; then
            session_phone="bt"
        fi
    fi

    # Detect IGN transitions
    if [ "$prev_ign" = "1" ] && [ "$ign" = "0" ]; then
        ign_off_since="$now"
        door_opened_after_off=0
        door_closed_since=0
        wait_phone_logged=0
        locked_this_cycle=0
        if [ "$wifi_conn" = "1" ]; then
            session_phone="wifi"
        elif [ "$session_phone" != "wifi" ] && [ "$bt_conn" = "1" ]; then
            session_phone="bt"
        fi
        log_msg "EVENT: IGN turned OFF (enabled=$enabled, delay=${delay}s, ignore_hatch=$ignore_hatch, phone=$session_phone, wifi=$wifi_conn, bt=$bt_conn)"
    elif [ "$prev_ign" = "0" ] && [ "$ign" = "1" ]; then
        ign_off_since=0
        door_opened_after_off=0
        door_closed_since=0
        wait_phone_logged=0
        locked_this_cycle=0
        session_phone="none"
        if [ "$wifi_conn" = "1" ]; then
            session_phone="wifi"
        elif [ "$bt_conn" = "1" ]; then
            session_phone="bt"
        fi
        # Keep local log bounded (~200 lines max)
        if [ -f "$LOCAL_LOG" ]; then
            tail -n 200 "$LOCAL_LOG" > "$LOCAL_LOG.tmp" 2>/dev/null && mv "$LOCAL_LOG.tmp" "$LOCAL_LOG" 2>/dev/null
            chmod 666 "$LOCAL_LOG" 2>/dev/null
        fi
        log_msg "EVENT: IGN turned ON (phone=$session_phone)"
    fi
    prev_ign="$ign"

    # Read door_state ("<any_cabin_open> <all_cabin_closed> <trunk_open>")
    any_c=0
    all_c=1
    trunk_o=0
    if [ -f "$DOOR_FILE" ]; then
        read -r any_c all_c trunk_o < "$DOOR_FILE" 2>/dev/null
    fi
    compute_door_flags "$any_c" "$all_c" "$trunk_o" "$ignore_hatch"

    # Also detect phone if it connected right as IGN turned OFF before doors closed
    if [ "$ign" = "0" ] && [ "$door_closed_since" = "0" ]; then
        if [ "$wifi_conn" = "1" ]; then
            session_phone="wifi"
        elif [ "$session_phone" != "wifi" ] && [ "$bt_conn" = "1" ]; then
            session_phone="bt"
        fi
    fi

    # State machine when IGN is OFF
    countdown_str="idle"
    if [ "$ign" = "0" ] && [ "$ign_off_since" -gt 0 ]; then
        ign_elapsed=$((now - ign_off_since))
        if [ "$enabled" = "1" ] && [ "$locked_this_cycle" = "0" ]; then
            if [ "$eff_any" = "1" ]; then
                if [ "$door_opened_after_off" = "0" ]; then
                    log_msg "EVENT: Door opened after IGN OFF (ignore_hatch=$ignore_hatch, trunk=$trunk_o)"
                fi
                door_opened_after_off=1
                door_closed_since=0
                countdown_str="door_open"
            elif [ "$door_opened_after_off" = "0" ]; then
                countdown_str="wait_door_open (+${ign_elapsed}s)"
            elif [ "$eff_closed" != "1" ]; then
                door_closed_since=0
                countdown_str="wait_all_closed"
            else
                if [ "$door_closed_since" = "0" ]; then
                    door_closed_since="$now"
                    log_msg "EVENT: Last door closed -> starting ${delay}s countdown (phone=$session_phone, ignore_hatch=$ignore_hatch)"
                fi
                elapsed=$((now - door_closed_since))
                rem=$((delay - elapsed))
                if [ "$rem" -le 0 ]; then
                    phone_ready=1
                    if [ "$session_phone" = "wifi" ] && [ "$wifi_fail_streak" -lt 2 ]; then
                        phone_ready=0
                        countdown_str="wait_wifi_drop (ping=${wifi_ms}ms, fail=${wifi_fail_streak}/2)"
                        if [ "$wait_phone_logged" = "0" ]; then
                            log_msg "WAIT_PHONE: Timer elapsed (+${elapsed}s); waiting for Wi-Fi Direct drop (ip=$wifi_ip, ping=${wifi_ms}ms)"
                            wait_phone_logged=1
                        fi
                    elif [ "$session_phone" = "bt" ] && [ "$bt_conn" = "1" ]; then
                        phone_ready=0
                        countdown_str="wait_bt_drop"
                        if [ "$wait_phone_logged" = "0" ]; then
                            log_msg "WAIT_PHONE: Timer elapsed (+${elapsed}s); waiting for Bluetooth disconnect"
                            wait_phone_logged=1
                        fi
                    fi

                    if [ "$phone_ready" = "1" ]; then
                        read -r any_now all_now trunk_now < "$DOOR_FILE" 2>/dev/null
                        compute_door_flags "$any_now" "$all_now" "$trunk_now" "$ignore_hatch"
                        if [ "$eff_closed" = "1" ]; then
                            log_msg "ACTION: Locking doors at +${elapsed}s (phone=$session_phone, wifi_fail=$wifi_fail_streak, bt=$bt_conn, trunk=$trunk_now, ignore_hatch=$ignore_hatch)"
                            res=$(service call com.hkmc.micom.bcm 5 i32 1 2>&1 | one_line)
                            log_msg "RESULT: lockDoor(true) -> $res"
                            locked_this_cycle=1
                            countdown_str="locked_at_+${elapsed}s"
                        else
                            log_msg "SKIP_LOCK: Door/hatch open at final check (all_c=$all_now, trunk=$trunk_now)"
                            door_closed_since=0
                            countdown_str="aborted_door_open"
                        fi
                    fi
                else
                    countdown_str="locking_in_${rem}s"
                fi
            fi
        elif [ "$locked_this_cycle" = "1" ]; then
            countdown_str="locked"
        else
            countdown_str="ign_off_+${ign_elapsed}s (disabled)"
        fi

        if [ "$((now - last_ign_off_hb))" -ge 10 ]; then
            last_ign_off_hb="$now"
            log_msg "HEARTBEAT: state=$countdown_str phone=$session_phone wifi=$wifi_conn(${wifi_ms}ms) bt=$bt_conn doors=${any_c}/${all_c}/${trunk_o}"
        fi
    fi

    {
        echo "ign=$ign"
        echo "enabled=$enabled"
        echo "delay=$delay"
        echo "ignore_hatch=$ignore_hatch"
        echo "phone=$session_phone"
        echo "wifi=$wifi_conn"
        echo "bt=$bt_conn"
        echo "state=$countdown_str"
        echo "updated=$now"
    } > "$STATUS_FILE"
    chmod 666 "$STATUS_FILE" 2>/dev/null

    sleep 1
done
