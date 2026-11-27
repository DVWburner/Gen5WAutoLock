#!/system/bin/sh
export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib

BASE="/data/nemesis/autolock"
mkdir -p "$BASE"
chmod 777 "$BASE"

CFG_ENABLED="$BASE/enabled"
CFG_DELAY="$BASE/delay"
CFG_IGNORE_HATCH="$BASE/ignore_hatch"
CFG_REQUIRE_PHONE="$BASE/require_phone"
DOOR_FILE="$BASE/door_state"
LOCK_STATE_FILE="$BASE/lock_state"
BT_CONN_FILE="$BASE/bt_connected"
WIFI_STATE_FILE="$BASE/wifi_state"
STATUS_FILE="$BASE/status"
LOCAL_LOG="$BASE/autolock.log"

[ -f "$CFG_ENABLED" ] || echo "0" > "$CFG_ENABLED"
[ -f "$CFG_DELAY" ] || echo "10" > "$CFG_DELAY"
[ -f "$CFG_IGNORE_HATCH" ] || echo "1" > "$CFG_IGNORE_HATCH"
[ -f "$CFG_REQUIRE_PHONE" ] || echo "1" > "$CFG_REQUIRE_PHONE"
[ -f "$DOOR_FILE" ] || echo "0 1 0" > "$DOOR_FILE"
[ -f "$LOCK_STATE_FILE" ] || echo "0 1 1 1 1" > "$LOCK_STATE_FILE"
[ -f "$BT_CONN_FILE" ] || echo "0" > "$BT_CONN_FILE"
[ -f "$WIFI_STATE_FILE" ] || echo "0 0 none" > "$WIFI_STATE_FILE"
chmod 666 "$CFG_ENABLED" "$CFG_DELAY" "$CFG_IGNORE_HATCH" "$CFG_REQUIRE_PHONE" "$DOOR_FILE" "$LOCK_STATE_FILE" "$BT_CONN_FILE" "$WIFI_STATE_FILE" 2>/dev/null

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

get_wifi_arp_ips() {
    if [ ! -f /proc/net/arp ]; then
        return 1
    fi
    p2p_ips=""
    wlan_ips=""
    while read -r ip hwtype flags mac mask dev; do
        case "$ip" in
            IP*|[a-zA-Z]*) continue ;;
        esac
        if [ "$flags" != "0x0" ] && [ -n "$mac" ] && [ "$mac" != "00:00:00:00:00:00" ]; then
            case "$dev" in
                p2p*)
                    p2p_ips="$p2p_ips $ip"
                    ;;
                wlan*)
                    wlan_ips="$wlan_ips $ip"
                    ;;
            esac
        fi
    done < /proc/net/arp
    all_ips="$p2p_ips $wlan_ips"
    for x in $all_ips; do
        echo "$all_ips"
        return 0
    done
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
    pref_ip="$1"
    cand_ips=$(get_wifi_arp_ips)
    if [ -z "$cand_ips" ]; then
        echo "0 0 none"
        return 0
    fi
    ordered=""
    if [ -n "$pref_ip" ] && [ "$pref_ip" != "none" ]; then
        for ip in $cand_ips; do
            if [ "$ip" = "$pref_ip" ]; then
                ordered="$pref_ip"
                break
            fi
        done
    fi
    for ip in $cand_ips; do
        if [ "$ip" != "$pref_ip" ]; then
            ordered="$ordered $ip"
        fi
    done
    tried=0
    first_ip="none"
    for peer_ip in $ordered; do
        [ "$first_ip" = "none" ] && first_ip="$peer_ip"
        tried=$((tried + 1))
        [ "$tried" -gt 3 ] && break
        pout=$(ping -c 1 -W 1 "$peer_ip" 2>&1)
        if [ $? -eq 0 ]; then
            ms=$(echo "$pout" | extract_ping_ms)
            [ -z "$ms" ] && ms=1
            echo "1 $ms $peer_ip"
            return 0
        fi
    done
    echo "0 0 $first_ip"
    return 0
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
cabin_seq_at_off=""
all_seq_at_off=""
wait_phone_logged=0
locked_this_cycle=0
cycle_done_state="locked"
session_phone="none"
last_wifi_ip="none"
wifi_fail_streak=0
last_ign_off_hb=0
svc_check_tick=0

log_msg "autolock-daemon started (v1.6.0)"

# Ensure Android bridge service is running so door_state & bt_connected stay updated
am startservice -n com.gen5w.autolock/.AutoLockBridgeService >/dev/null 2>&1 || true

while true; do
    now=$(date +%s 2>/dev/null)
    [ -z "$now" ] && now=0

    svc_check_tick=$((svc_check_tick + 1))
    if [ "$svc_check_tick" -ge 15 ]; then
        svc_check_tick=0
        if ! ps 2>/dev/null | grep "com\.gen5w\.autolock" >/dev/null; then
            am startservice -n com.gen5w.autolock/.AutoLockBridgeService >/dev/null 2>&1 || true
        fi
    fi

    enabled=""
    delay=""
    ignore_hatch=""
    require_phone=""
    [ -f "$CFG_ENABLED" ] && read -r enabled < "$CFG_ENABLED" 2>/dev/null
    [ -f "$CFG_DELAY" ] && read -r delay < "$CFG_DELAY" 2>/dev/null
    [ -f "$CFG_IGNORE_HATCH" ] && read -r ignore_hatch < "$CFG_IGNORE_HATCH" 2>/dev/null
    [ -f "$CFG_REQUIRE_PHONE" ] && read -r require_phone < "$CFG_REQUIRE_PHONE" 2>/dev/null
    case "$enabled" in 1) enabled=1 ;; *) enabled=0 ;; esac
    case "$ignore_hatch" in 0) ignore_hatch=0 ;; *) ignore_hatch=1 ;; esac
    case "$require_phone" in 0) require_phone=0 ;; *) require_phone=1 ;; esac
    case "$delay" in ''|*[!0-9]*) delay=10 ;; esac
    [ "$delay" -lt 5 ] && delay=5
    [ "$delay" -gt 20 ] && delay=20

    # Poll Wi-Fi Direct peer reachability & Bluetooth state
    wifi_res=$(check_wifi_peer "$last_wifi_ip")
    set -- $wifi_res
    wifi_conn="$1"
    wifi_ms="$2"
    wifi_ip="$3"
    echo "$wifi_conn $wifi_ms $wifi_ip" > "$WIFI_STATE_FILE.tmp"
    chmod 666 "$WIFI_STATE_FILE.tmp" 2>/dev/null
    mv "$WIFI_STATE_FILE.tmp" "$WIFI_STATE_FILE" 2>/dev/null

    if [ "$wifi_conn" = "1" ]; then
        wifi_fail_streak=0
        last_wifi_ip="$wifi_ip"
    else
        wifi_fail_streak=$((wifi_fail_streak + 1))
    fi

    bt_conn=""
    [ -f "$BT_CONN_FILE" ] && read -r bt_conn < "$BT_CONN_FILE" 2>/dev/null
    case "$bt_conn" in 1) bt_conn=1 ;; *) bt_conn=0 ;; esac

    # Read IGN status (1 = ON, 0 = OFF; preserve previous on transient IPC failure)
    ign_raw=$(service call com.hkmc.micom.bcm 17 2>&1)
    if echo "$ign_raw" | grep -q "00000001"; then
        ign=1
    elif echo "$ign_raw" | grep -q "00000000"; then
        ign=0
    elif [ "$prev_ign" != "-1" ]; then
        ign="$prev_ign"
    else
        ign=0
    fi

    # Keep active session phone updated while IGN is ON (with 5s hysteresis against brief Wi-Fi drops)
    if [ "$ign" = "1" ]; then
        if [ "$wifi_conn" = "1" ]; then
            session_phone="wifi"
        elif [ "$bt_conn" = "1" ]; then
            if [ "$session_phone" != "wifi" ] || [ "$wifi_fail_streak" -ge 5 ]; then
                session_phone="bt"
            fi
        else
            if [ "$session_phone" = "wifi" ] && [ "$wifi_fail_streak" -ge 5 ]; then
                session_phone="none"
            elif [ "$session_phone" = "bt" ]; then
                session_phone="none"
            fi
        fi
    fi

    # Read door_state ("<any_cabin_open> <all_cabin_closed> <trunk_open> [cabin_seq] [all_seq]")
    any_c=0
    all_c=1
    trunk_o=0
    cabin_seq=""
    all_seq=""
    if [ -f "$DOOR_FILE" ]; then
        read -r any_c all_c trunk_o cabin_seq all_seq < "$DOOR_FILE" 2>/dev/null
    fi
    compute_door_flags "$any_c" "$all_c" "$trunk_o" "$ignore_hatch"

    # Detect IGN transitions
    if [ "$prev_ign" = "1" ] && [ "$ign" = "0" ]; then
        ign_off_since="$now"
        door_opened_after_off=0
        door_closed_since=0
        cabin_seq_at_off="$cabin_seq"
        all_seq_at_off="$all_seq"
        wait_phone_logged=0
        locked_this_cycle=0
        cycle_done_state="locked"
        if [ "$wifi_conn" = "1" ]; then
            session_phone="wifi"
        elif [ "$session_phone" = "wifi" ] && [ "$wifi_fail_streak" -ge 5 ]; then
            if [ "$bt_conn" = "1" ]; then
                session_phone="bt"
            else
                session_phone="none"
            fi
        elif [ "$session_phone" != "wifi" ] && [ "$bt_conn" = "1" ]; then
            session_phone="bt"
        elif [ "$session_phone" = "bt" ] && [ "$bt_conn" = "0" ]; then
            session_phone="none"
        fi
        log_msg "EVENT: IGN turned OFF (enabled=$enabled, delay=${delay}s, ignore_hatch=$ignore_hatch, require_phone=$require_phone, phone=$session_phone, wifi=$wifi_conn, bt=$bt_conn)"
    elif [ "$prev_ign" = "0" ] && [ "$ign" = "1" ]; then
        ign_off_since=0
        door_opened_after_off=0
        door_closed_since=0
        cabin_seq_at_off=""
        all_seq_at_off=""
        wait_phone_logged=0
        locked_this_cycle=0
        cycle_done_state="locked"
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

    # Read lock_state ("<all_locked> <drv_u> <pas_u> <rl_u> <rr_u>", where 0=locked 1=unlocked per door)
    all_locked=0
    drv_u=1
    pas_u=1
    rl_u=1
    rr_u=1
    if [ -f "$LOCK_STATE_FILE" ]; then
        read -r all_locked drv_u pas_u rl_u rr_u < "$LOCK_STATE_FILE" 2>/dev/null
    fi
    [ -z "$all_locked" ] && all_locked=0

    # State machine when IGN is OFF
    countdown_str="idle"
    if [ "$ign" = "0" ] && [ "$ign_off_since" -gt 0 ]; then
        ign_elapsed=$((now - ign_off_since))

        # Re-arm if a monitored door physically opens after a completed lock/skip cycle while IGN stays OFF
        if [ "$enabled" = "1" ] && [ "$locked_this_cycle" = "1" ] && [ "$eff_any" = "1" ]; then
            locked_this_cycle=0
            door_opened_after_off=1
            door_closed_since=0
            wait_phone_logged=0
            if [ "$wifi_conn" = "1" ]; then
                session_phone="wifi"
            elif [ "$bt_conn" = "1" ]; then
                session_phone="bt"
            else
                session_phone="none"
            fi
            log_msg "EVENT: Door opened after cycle completed -> re-arming (phone=$session_phone, ignore_hatch=$ignore_hatch, trunk=$trunk_o)"
        fi

        # Upgrade phone detection if phone finishes connecting during exit / countdown
        if [ "$locked_this_cycle" = "0" ]; then
            if [ "$wifi_conn" = "1" ]; then
                session_phone="wifi"
            elif [ "$session_phone" = "none" ] && [ "$bt_conn" = "1" ]; then
                session_phone="bt"
            fi
        fi

        if [ "$ignore_hatch" = "1" ]; then
            eff_seq="$cabin_seq"
            eff_seq_at_off="$cabin_seq_at_off"
        else
            eff_seq="$all_seq"
            eff_seq_at_off="$all_seq_at_off"
        fi

        if [ "$enabled" = "1" ] && [ "$locked_this_cycle" = "0" ]; then
            if [ "$eff_any" = "1" ]; then
                if [ "$door_opened_after_off" = "0" ]; then
                    log_msg "EVENT: Door opened after IGN OFF (ignore_hatch=$ignore_hatch, trunk=$trunk_o)"
                elif [ "$door_closed_since" != "0" ]; then
                    log_msg "EVENT: Door re-opened -> resetting countdown (ignore_hatch=$ignore_hatch, trunk=$trunk_o)"
                fi
                door_opened_after_off=1
                door_closed_since=0
                wait_phone_logged=0
                countdown_str="door_open"
            elif [ "$door_opened_after_off" = "0" ] && [ -n "$eff_seq" ] && [ -n "$eff_seq_at_off" ] && [ "$eff_seq" != "$eff_seq_at_off" ]; then
                door_opened_after_off=1
                door_closed_since="$now"
                log_msg "EVENT: Rapid door open/close detected via seq ($eff_seq_at_off -> $eff_seq) -> starting ${delay}s countdown (phone=$session_phone)"
                countdown_str="locking_in_${delay}s"
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
                    if [ "$session_phone" = "none" ] && [ "$require_phone" = "1" ]; then
                        log_msg "SKIP_LOCK: No phone detected at +${elapsed}s (require_phone=1, wifi=$wifi_conn, bt=$bt_conn)"
                        locked_this_cycle=1
                        cycle_done_state="skipped_no_phone"
                        countdown_str="skipped_no_phone_+${elapsed}s"
                    else
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
                            any_now=""
                            all_now=""
                            trunk_now=""
                            seq_now=""
                            if [ -f "$DOOR_FILE" ]; then
                                read -r any_now all_now trunk_now seq_rest < "$DOOR_FILE" 2>/dev/null
                            fi
                            if [ -z "$any_now" ] || [ -z "$all_now" ]; then
                                log_msg "SKIP_LOCK: Door state unavailable at final check"
                                door_closed_since=0
                                countdown_str="aborted_door_unavail"
                            else
                                compute_door_flags "$any_now" "$all_now" "$trunk_now" "$ignore_hatch"
                                all_locked_now=0
                                drv_u_now=1
                                pas_u_now=1
                                rl_u_now=1
                                rr_u_now=1
                                if [ -f "$LOCK_STATE_FILE" ]; then
                                    read -r all_locked_now drv_u_now pas_u_now rl_u_now rr_u_now < "$LOCK_STATE_FILE" 2>/dev/null
                                fi
                                if [ "$eff_closed" != "1" ]; then
                                    log_msg "SKIP_LOCK: Door/hatch open at final check (all_c=$all_now, trunk=$trunk_now)"
                                    door_closed_since=0
                                    countdown_str="aborted_door_open"
                                elif [ "$all_locked_now" = "1" ]; then
                                    log_msg "SKIP_LOCK: Doors already locked at +${elapsed}s (lock=${drv_u_now}/${pas_u_now}/${rl_u_now}/${rr_u_now}, phone=$session_phone)"
                                    locked_this_cycle=1
                                    cycle_done_state="already_locked"
                                    countdown_str="already_locked_at_+${elapsed}s"
                                else
                                    log_msg "ACTION: Locking doors at +${elapsed}s (phone=$session_phone, require_phone=$require_phone, wifi_fail=$wifi_fail_streak, bt=$bt_conn, trunk=$trunk_now, ignore_hatch=$ignore_hatch, lock=${drv_u_now}/${pas_u_now}/${rl_u_now}/${rr_u_now})"
                                    res=$(service call com.hkmc.micom.bcm 5 i32 1 2>&1 | one_line)
                                    log_msg "RESULT: lockDoor(true) -> $res"
                                    locked_this_cycle=1
                                    cycle_done_state="locked"
                                    countdown_str="locked_at_+${elapsed}s"
                                fi
                            fi
                        fi
                    fi
                else
                    countdown_str="locking_in_${rem}s"
                fi
            fi
        elif [ "$locked_this_cycle" = "1" ]; then
            countdown_str="$cycle_done_state"
        else
            countdown_str="ign_off_+${ign_elapsed}s (disabled)"
        fi

        if [ "$((now - last_ign_off_hb))" -ge 10 ]; then
            last_ign_off_hb="$now"
            log_msg "HEARTBEAT: state=$countdown_str phone=$session_phone req_phone=$require_phone wifi=$wifi_conn(${wifi_ms}ms) bt=$bt_conn doors=${any_c}/${all_c}/${trunk_o} lock=${all_locked}(${drv_u}/${pas_u}/${rl_u}/${rr_u})"
        fi
    fi

    {
        echo "ign=$ign"
        echo "enabled=$enabled"
        echo "delay=$delay"
        echo "ignore_hatch=$ignore_hatch"
        echo "require_phone=$require_phone"
        echo "phone=$session_phone"
        echo "wifi=$wifi_conn"
        echo "bt=$bt_conn"
        echo "locked=$all_locked"
        echo "state=$countdown_str"
        echo "updated=$now"
    } > "$STATUS_FILE.tmp"
    chmod 666 "$STATUS_FILE.tmp" 2>/dev/null
    mv "$STATUS_FILE.tmp" "$STATUS_FILE" 2>/dev/null

    sleep 1
done
