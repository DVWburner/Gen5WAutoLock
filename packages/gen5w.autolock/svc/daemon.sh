#!/system/bin/sh
export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib

BASE="/data/nemesis/autolock"
mkdir -p "$BASE"
chmod 777 "$BASE"

CFG_ENABLED="$BASE/enabled"
CFG_DELAY="$BASE/delay"
CFG_BT_REQ="$BASE/bt_req"
DOOR_FILE="$BASE/door_state"
BT_CONN_FILE="$BASE/bt_connected"
CMD_FILE="$BASE/cmd"
STATUS_FILE="$BASE/status"
LOCAL_LOG="$BASE/autolock.log"

[ -f "$CFG_ENABLED" ] || echo "0" > "$CFG_ENABLED"
[ -f "$CFG_DELAY" ] || echo "10" > "$CFG_DELAY"
[ -f "$CFG_BT_REQ" ] || echo "0" > "$CFG_BT_REQ"
[ -f "$DOOR_FILE" ] || echo "0 1" > "$DOOR_FILE"
[ -f "$BT_CONN_FILE" ] || echo "0" > "$BT_CONN_FILE"
chmod 666 "$CFG_ENABLED" "$CFG_DELAY" "$CFG_BT_REQ" "$DOOR_FILE" "$BT_CONN_FILE" 2>/dev/null

log_msg() {
    ts=$(date "+%Y-%m-%d %H:%M:%S" 2>/dev/null)
    line="[$ts] $1"
    echo "$line" >> "$LOCAL_LOG"
    chmod 666 "$LOCAL_LOG" 2>/dev/null
}

one_line() {
    # Pure sh replacement for `tr '\n' ' '` (KitKat toolbox has no tr)
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

log_bt_state() {
    bt_dump=$(dumpsys bluetooth_manager 2>&1 | grep -i -e "enabled" -e "state:" -e "Bonded" -e "Connected" -e "mDevices" | head -n 20 | one_line)
    if [ -n "$bt_dump" ]; then
        log_msg "BT_STATE: $bt_dump"
    fi
}

# Initialize state
prev_ign="-1"
ign_off_since=0
door_opened_after_off=0
door_closed_since=0
bt_wait_logged=0
locked_this_cycle=0
last_bt_log=0

log_msg "autolock-daemon started"
log_bt_state

# Ensure Android bridge service is running so door_state & bt_connected stay updated
am startservice -n com.gen5w.autolock/.AutoLockBridgeService >/dev/null 2>&1 || true

while true; do
    now=$(date +%s 2>/dev/null)
    [ -z "$now" ] && now=0

    enabled=$(cat "$CFG_ENABLED" 2>/dev/null)
    delay=$(cat "$CFG_DELAY" 2>/dev/null)
    [ -z "$enabled" ] && enabled=0
    [ -z "$delay" ] && delay=10

    # Read IGN status (1 = ON, 0 = OFF)
    ign_raw=$(service call com.hkmc.micom.bcm 17 2>&1)
    if echo "$ign_raw" | grep -q "00000001"; then
        ign=1
    else
        ign=0
    fi

    # Detect IGN ON -> OFF transition
    if [ "$prev_ign" = "1" ] && [ "$ign" = "0" ]; then
        ign_off_since="$now"
        door_opened_after_off=0
        door_closed_since=0
        bt_wait_logged=0
        locked_this_cycle=0
        log_msg "EVENT: IGN turned OFF (auto-lock=$enabled, delay=${delay}s, bt_req=$(cat "$CFG_BT_REQ" 2>/dev/null))"
        log_bt_state
    elif [ "$prev_ign" = "0" ] && [ "$ign" = "1" ]; then
        ign_off_since=0
        door_opened_after_off=0
        door_closed_since=0
        bt_wait_logged=0
        locked_this_cycle=0
        log_msg "EVENT: IGN turned ON"
        log_bt_state
    fi
    prev_ign="$ign"

    # Read door_state ("<any_cabin_open> <all_cabin_closed>") and bt_connected ("0"|"1")
    any_open=0
    all_closed=1
    if [ -f "$DOOR_FILE" ]; then
        read -r any_open all_closed < "$DOOR_FILE" 2>/dev/null
        [ -z "$any_open" ] && any_open=0
        [ -z "$all_closed" ] && all_closed=1
    fi
    bt_req=$(cat "$CFG_BT_REQ" 2>/dev/null)
    [ -z "$bt_req" ] && bt_req=0
    bt_conn=$(cat "$BT_CONN_FILE" 2>/dev/null)
    [ -z "$bt_conn" ] && bt_conn=0

    # Countdown & lock execution when IGN is OFF (excludes hatch/trunk)
    countdown_str="idle"
    if [ "$ign" = "0" ] && [ "$ign_off_since" -gt 0 ]; then
        ign_elapsed=$((now - ign_off_since))
        if [ "$enabled" = "1" ] && [ "$locked_this_cycle" = "0" ]; then
            if [ "$any_open" = "1" ]; then
                if [ "$door_opened_after_off" = "0" ]; then
                    log_msg "EVENT: Cabin door opened after IGN OFF"
                fi
                door_opened_after_off=1
                door_closed_since=0
                countdown_str="cabin_door_open"
            elif [ "$door_opened_after_off" = "0" ]; then
                countdown_str="wait_cabin_door (+${ign_elapsed}s)"
            elif [ "$all_closed" != "1" ]; then
                door_closed_since=0
                countdown_str="wait_all_cabin_closed"
            else
                if [ "$door_closed_since" = "0" ]; then
                    door_closed_since="$now"
                    log_msg "EVENT: Last cabin door closed (hatch excluded) -> starting ${delay}s countdown"
                fi
                elapsed=$((now - door_closed_since))
                rem=$((delay - elapsed))
                if [ "$rem" -le 0 ]; then
                    if [ "$bt_req" = "1" ] && [ "$bt_conn" = "1" ]; then
                        countdown_str="wait_bt_disconnect"
                        if [ "$bt_wait_logged" = "0" ]; then
                            log_msg "WAIT_BT: Countdown elapsed (+${elapsed}s) but BT still connected; waiting..."
                            bt_wait_logged=1
                        fi
                    else
                        # Final check that all 4 cabin doors are still closed right before locking
                        read -r any_open_now all_closed_now < "$DOOR_FILE" 2>/dev/null
                        if [ "$all_closed_now" = "1" ]; then
                            log_msg "ACTION: All 4 cabin doors closed -> lockDoor(true) at +${elapsed}s after last door closed (bt_req=$bt_req, bt_conn=$bt_conn)"
                            res=$(service call com.hkmc.micom.bcm 5 i32 1 2>&1 | one_line)
                            log_msg "RESULT: lockDoor(true) -> $res"
                            locked_this_cycle=1
                            countdown_str="locked_at_+${elapsed}s"
                        else
                            log_msg "SKIP_LOCK: Cabin door open at final check (all_closed=$all_closed_now)"
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
    fi

    # Periodic BT log every 5s while IGN is OFF (to measure wake window + BT drop)
    if [ "$ign" = "0" ] && [ "$((now - last_bt_log))" -ge 5 ]; then
        last_bt_log="$now"
        log_bt_state
    fi

    # Process any UI commands
    if [ -f "$CMD_FILE" ]; then
        cmd=$(cat "$CMD_FILE" 2>/dev/null)
        rm -f "$CMD_FILE"
        case "$cmd" in
            test_lock)
                log_msg "CMD: manual lockDoor(true) -> 2s -> lockDoor(false)"
                service call com.hkmc.micom.bcm 5 i32 1 >> "$LOCAL_LOG" 2>&1
                sleep 2
                service call com.hkmc.micom.bcm 5 i32 0 >> "$LOCAL_LOG" 2>&1
                ;;
            snapshot)
                log_bt_state
                ;;
        esac
    fi

    {
        echo "ign=$ign"
        echo "enabled=$enabled"
        echo "delay=$delay"
        echo "state=$countdown_str"
        echo "updated=$now"
    } > "$STATUS_FILE"
    chmod 666 "$STATUS_FILE" 2>/dev/null

    sleep 1
done
