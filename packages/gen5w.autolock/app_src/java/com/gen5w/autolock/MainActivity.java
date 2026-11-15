package com.gen5w.autolock;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

public class MainActivity extends Activity {
    public static final String APP_VERSION = "v1.5.0";

    private static final String BASE_DIR = "/data/nemesis/autolock";
    private static final String FILE_ENABLED = BASE_DIR + "/enabled";
    private static final String FILE_DELAY = BASE_DIR + "/delay";
    private static final String FILE_IGNORE_HATCH = BASE_DIR + "/ignore_hatch";
    private static final String FILE_WIFI_STATE = BASE_DIR + "/wifi_state";
    private static final String FILE_BT_CONN = BASE_DIR + "/bt_connected";

    private static final int MIN_DELAY_SEC = 5;
    private static final int MAX_DELAY_SEC = 20;
    private static final int DEFAULT_DELAY_SEC = 10;
    private static final int DISCLAIMER_WAIT_SEC = 5;

    private static final int PHONE_NONE = 0;
    private static final int PHONE_BT = 1;
    private static final int PHONE_WIFI = 2;

    private final Handler handler = new Handler();
    private boolean updatingUi = false;
    private int currentDelaySec = DEFAULT_DELAY_SEC;
    private boolean ignoreHatch = true;
    private int detectedPhoneType = PHONE_NONE;

    private FrameLayout disclaimerOverlay;
    private LinearLayout mainContainer;
    private Button understandBtn;
    private int countdownRemaining = DISCLAIMER_WAIT_SEC;

    private Switch masterSwitch;
    private TextView masterBadge;
    private TextView savedBadge;

    // Dynamic workflow views
    private TextView stepTimerTitle;
    private LinearLayout stepPhonePanel;
    private View stepPhoneArrow;
    private TextView stepPhoneTitle;

    // Phone status box
    private View phoneLight;
    private TextView phoneStatusTitle;

    // Stepper & hatch checkbox
    private TextView delayNumberText;
    private CheckBox ignoreHatchCheck;

    private final Runnable disclaimerTick = new Runnable() {
        @Override
        public void run() {
            countdownRemaining--;
            if (countdownRemaining <= 0) {
                understandBtn.setEnabled(true);
                understandBtn.setText("I Understand");
                understandBtn.setBackground(makeRoundedBg(0xFF0284C7, 0xFF38BDF8, 10, 1));
                understandBtn.setTextColor(Color.WHITE);
            } else {
                understandBtn.setText("I Understand (" + countdownRemaining + "s)");
                handler.postDelayed(this, 1000);
            }
        }
    };

    private final Runnable hideSavedBadge = new Runnable() {
        @Override
        public void run() {
            if (savedBadge != null) {
                savedBadge.setVisibility(View.INVISIBLE);
            }
        }
    };

    private final Runnable pollStatusRunnable = new Runnable() {
        @Override
        public void run() {
            refreshPhoneDetection();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        startService(new Intent(this, AutoLockBridgeService.class));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0D1117);

        mainContainer = buildMainScreen();
        mainContainer.setVisibility(View.GONE);
        root.addView(mainContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        disclaimerOverlay = buildDisclaimerScreen();
        root.addView(disclaimerOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        loadConfigToUi();
        refreshPhoneDetection();
        handler.postDelayed(disclaimerTick, 1000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(pollStatusRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(pollStatusRunnable);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(disclaimerTick);
        handler.removeCallbacks(hideSavedBadge);
        handler.removeCallbacks(pollStatusRunnable);
        super.onDestroy();
    }

    private FrameLayout buildDisclaimerScreen() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xFF0B0F17);
        overlay.setPadding(dp(32), dp(24), dp(32), dp(24));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(makeRoundedBg(0xFF141B26, 0xFFF59E0B, 16, 2));
        card.setPadding(dp(32), dp(26), dp(32), dp(26));
        card.setGravity(Gravity.CENTER_HORIZONTAL);

        LinearLayout warnHeader = new LinearLayout(this);
        warnHeader.setOrientation(LinearLayout.HORIZONTAL);
        warnHeader.setGravity(Gravity.CENTER_VERTICAL);
        warnHeader.setPadding(0, 0, 0, dp(14));

        FlowIconView warnIcon = new FlowIconView(this, FlowIconView.ICON_WARNING);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        iconLp.rightMargin = dp(12);
        warnHeader.addView(warnIcon, iconLp);

        TextView warnTitle = new TextView(this);
        warnTitle.setText("Important Safety Notice");
        warnTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 23);
        warnTitle.setTypeface(Typeface.DEFAULT_BOLD);
        warnTitle.setTextColor(0xFFFBBF24);
        warnHeader.addView(warnTitle);
        card.addView(warnHeader);

        TextView msgPrimary = new TextView(this);
        msgPrimary.setText("Timed Auto-Lock DOES NOT check whether your key or smart fob is still inside the vehicle.");
        msgPrimary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        msgPrimary.setTypeface(Typeface.DEFAULT_BOLD);
        msgPrimary.setTextColor(Color.WHITE);
        msgPrimary.setGravity(Gravity.CENTER);
        msgPrimary.setPadding(0, 0, 0, dp(12));
        card.addView(msgPrimary);

        TextView msgSecondary = new TextView(this);
        msgSecondary.setText(
                "The doors will automatically lock after the timer expires even if the key is left inside.\n\n"
                + "Always be careful and keep an alternate way to unlock your car available "
                + "(such as the phone app, a physical key, or an alternate fob).");
        msgSecondary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        msgSecondary.setTextColor(0xFFCBD5E1);
        msgSecondary.setGravity(Gravity.CENTER);
        msgSecondary.setLineSpacing(dp(3), 1.0f);
        msgSecondary.setPadding(0, 0, 0, dp(22));
        card.addView(msgSecondary);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);

        Button exitBtn = new Button(this);
        exitBtn.setText("Exit");
        exitBtn.setAllCaps(false);
        exitBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        exitBtn.setTextColor(0xFFCBD5E1);
        exitBtn.setBackground(makeRoundedBg(0xFF1E293B, 0xFF334155, 10, 1));
        exitBtn.setPadding(dp(26), dp(12), dp(26), dp(12));
        exitBtn.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams exitLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        exitLp.rightMargin = dp(16);
        btnRow.addView(exitBtn, exitLp);

        understandBtn = new Button(this);
        understandBtn.setText("I Understand (" + DISCLAIMER_WAIT_SEC + "s)");
        understandBtn.setAllCaps(false);
        understandBtn.setEnabled(false);
        understandBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        understandBtn.setTypeface(Typeface.DEFAULT_BOLD);
        understandBtn.setTextColor(0xFF64748B);
        understandBtn.setBackground(makeRoundedBg(0xFF1E293B, 0xFF334155, 10, 1));
        understandBtn.setPadding(dp(32), dp(12), dp(32), dp(12));
        understandBtn.setOnClickListener(v -> {
            disclaimerOverlay.setVisibility(View.GONE);
            mainContainer.setVisibility(View.VISIBLE);
        });
        btnRow.addView(understandBtn);

        card.addView(btnRow);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                dp(680), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        overlay.addView(card, cardLp);
        return overlay;
    }

    private LinearLayout buildMainScreen() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(24), dp(14), dp(24), dp(14));

        // 1. Top Header Bar (Icon + Name + Version + Saved + Master Switch + Exit)
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(12));

        ImageView appIcon = new ImageView(this);
        appIcon.setImageResource(R.mipmap.ic_launcher);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(38), dp(38));
        iconLp.rightMargin = dp(12);
        header.addView(appIcon, iconLp);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        header.addView(title);

        TextView versionLabel = new TextView(this);
        versionLabel.setText(APP_VERSION);
        versionLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        versionLabel.setTextColor(0xFF64748B);
        LinearLayout.LayoutParams verLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        verLp.leftMargin = dp(10);
        header.addView(versionLabel, verLp);

        savedBadge = new TextView(this);
        savedBadge.setText("\u2713 Saved");
        savedBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        savedBadge.setTypeface(Typeface.DEFAULT_BOLD);
        savedBadge.setTextColor(0xFF34D399);
        savedBadge.setBackground(makeRoundedBg(0xFF064E3B, 0xFF10B981, 8, 1));
        savedBadge.setPadding(dp(10), dp(4), dp(10), dp(4));
        savedBadge.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams savedLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        savedLp.rightMargin = dp(12);
        header.addView(savedBadge, savedLp);

        masterBadge = new TextView(this);
        masterBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        masterBadge.setTypeface(Typeface.DEFAULT_BOLD);
        masterBadge.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeLp.rightMargin = dp(8);
        header.addView(masterBadge, badgeLp);

        masterSwitch = new Switch(this);
        masterSwitch.setOnCheckedChangeListener((btn, checked) -> {
            updateMasterBadge(checked);
            if (!updatingUi) {
                saveSetting(FILE_ENABLED, checked ? "1" : "0");
            }
        });
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        swLp.rightMargin = dp(16);
        header.addView(masterSwitch, swLp);

        Button exitBtn = new Button(this);
        exitBtn.setText("Exit");
        exitBtn.setAllCaps(false);
        exitBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        exitBtn.setTypeface(Typeface.DEFAULT_BOLD);
        exitBtn.setTextColor(0xFFE2E8F0);
        exitBtn.setBackground(makeRoundedBg(0xFF1E293B, 0xFF334155, 8, 1));
        exitBtn.setPadding(dp(20), dp(8), dp(20), dp(8));
        exitBtn.setOnClickListener(v -> finish());
        header.addView(exitBtn);

        container.addView(header);

        // 2. Dynamic Logic Workflow Panels Card
        LinearLayout flowCard = new LinearLayout(this);
        flowCard.setOrientation(LinearLayout.HORIZONTAL);
        flowCard.setGravity(Gravity.CENTER_VERTICAL);
        flowCard.setBackground(makeRoundedBg(0xFF161B22, 0xFF263040, 14, 1));
        flowCard.setPadding(dp(16), dp(16), dp(16), dp(16));

        flowCard.addView(buildFlowPanel(FlowIconView.ICON_IGNITION, "Ignition off and leave car"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        flowCard.addView(buildFlowArrow());

        LinearLayout timerPanel = buildFlowPanel(
                FlowIconView.ICON_TIMER,
                buildTimerPanelLabel());
        stepTimerTitle = (TextView) timerPanel.findViewWithTag("panel_title");
        flowCard.addView(timerPanel,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.35f));

        stepPhoneArrow = buildFlowArrow();
        flowCard.addView(stepPhoneArrow);

        stepPhonePanel = buildFlowPanel(
                FlowIconView.ICON_SIGNAL,
                "Wi-Fi Direct Phone signal gets weak");
        stepPhoneTitle = (TextView) stepPhonePanel.findViewWithTag("panel_title");
        flowCard.addView(stepPhonePanel,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f));

        flowCard.addView(buildFlowArrow());
        flowCard.addView(buildFlowPanel(FlowIconView.ICON_LOCK, "Lock Doors"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.85f));

        LinearLayout.LayoutParams flowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flowLp.bottomMargin = dp(14);
        container.addView(flowCard, flowLp);

        // 3. Middle Row: Phone Status Box (Left) + Big Touch Delay Number Box (Right)
        LinearLayout midRow = new LinearLayout(this);
        midRow.setOrientation(LinearLayout.HORIZONTAL);

        // Left: Phone Detected Box
        LinearLayout phoneCard = new LinearLayout(this);
        phoneCard.setOrientation(LinearLayout.HORIZONTAL);
        phoneCard.setGravity(Gravity.CENTER_VERTICAL);
        phoneCard.setBackground(makeRoundedBg(0xFF161B22, 0xFF263040, 14, 1));
        phoneCard.setPadding(dp(22), dp(18), dp(22), dp(18));

        phoneLight = new View(this);
        LinearLayout.LayoutParams lightLp = new LinearLayout.LayoutParams(dp(22), dp(22));
        lightLp.rightMargin = dp(16);
        phoneCard.addView(phoneLight, lightLp);

        LinearLayout phoneTextCol = new LinearLayout(this);
        phoneTextCol.setOrientation(LinearLayout.VERTICAL);

        TextView phoneHdr = new TextView(this);
        phoneHdr.setText("Phone Proximity Monitor (Automatic)");
        phoneHdr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        phoneHdr.setTextColor(0xFF94A3B8);
        phoneTextCol.addView(phoneHdr);

        phoneStatusTitle = new TextView(this);
        phoneStatusTitle.setText("No Phone Detected, Timer only");
        phoneStatusTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        phoneStatusTitle.setTypeface(Typeface.DEFAULT_BOLD);
        phoneStatusTitle.setTextColor(Color.WHITE);
        phoneStatusTitle.setPadding(0, dp(3), 0, 0);
        phoneTextCol.addView(phoneStatusTitle);

        phoneCard.addView(phoneTextCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1.15f);
        leftLp.rightMargin = dp(14);
        midRow.addView(phoneCard, leftLp);

        // Right: Number Box with large - and + buttons (5-20 seconds)
        LinearLayout delayCard = new LinearLayout(this);
        delayCard.setOrientation(LinearLayout.VERTICAL);
        delayCard.setGravity(Gravity.CENTER);
        delayCard.setBackground(makeRoundedBg(0xFF161B22, 0xFF263040, 14, 1));
        delayCard.setPadding(dp(20), dp(14), dp(20), dp(14));

        TextView delayHdr = new TextView(this);
        delayHdr.setText("Lock Delay (5 - 20 sec)");
        delayHdr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        delayHdr.setTextColor(0xFF94A3B8);
        delayHdr.setPadding(0, 0, 0, dp(8));
        delayCard.addView(delayHdr);

        LinearLayout stepperRow = new LinearLayout(this);
        stepperRow.setOrientation(LinearLayout.HORIZONTAL);
        stepperRow.setGravity(Gravity.CENTER);

        Button minusBtn = makeBigStepperButton("-");
        minusBtn.setOnClickListener(v -> adjustDelay(-1));
        stepperRow.addView(minusBtn);

        delayNumberText = new TextView(this);
        delayNumberText.setText(currentDelaySec + "s");
        delayNumberText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        delayNumberText.setTypeface(Typeface.DEFAULT_BOLD);
        delayNumberText.setTextColor(0xFF38BDF8);
        delayNumberText.setGravity(Gravity.CENTER);
        delayNumberText.setBackground(makeRoundedBg(0xFF0F172A, 0xFF1E3A5F, 10, 1));
        LinearLayout.LayoutParams numLp = new LinearLayout.LayoutParams(dp(110), dp(54));
        numLp.leftMargin = dp(14);
        numLp.rightMargin = dp(14);
        stepperRow.addView(delayNumberText, numLp);

        Button plusBtn = makeBigStepperButton("+");
        plusBtn.setOnClickListener(v -> adjustDelay(1));
        stepperRow.addView(plusBtn);

        delayCard.addView(stepperRow);

        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 0.85f);
        midRow.addView(delayCard, rightLp);

        LinearLayout.LayoutParams midLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        midLp.bottomMargin = dp(12);
        container.addView(midRow, midLp);

        // 4. Bottom Card: Ignore Hatchback Checkbox (Default ON)
        LinearLayout hatchCard = new LinearLayout(this);
        hatchCard.setOrientation(LinearLayout.HORIZONTAL);
        hatchCard.setGravity(Gravity.CENTER_VERTICAL);
        hatchCard.setBackground(makeRoundedBg(0xFF161B22, 0xFF263040, 12, 1));
        hatchCard.setPadding(dp(20), dp(10), dp(20), dp(10));

        ignoreHatchCheck = new CheckBox(this);
        ignoreHatchCheck.setText("Ignore Hatchback being open");
        ignoreHatchCheck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        ignoreHatchCheck.setTypeface(Typeface.DEFAULT_BOLD);
        ignoreHatchCheck.setTextColor(Color.WHITE);
        ignoreHatchCheck.setPadding(dp(8), 0, 0, 0);
        ignoreHatchCheck.setOnCheckedChangeListener((btn, checked) -> {
            ignoreHatch = checked;
            updateWorkflowPanels();
            if (!updatingUi) {
                saveSetting(FILE_IGNORE_HATCH, checked ? "1" : "0");
            }
        });
        hatchCard.addView(ignoreHatchCheck, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        container.addView(hatchCard);

        return container;
    }

    private LinearLayout buildFlowPanel(int iconType, String title) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(dp(6), dp(4), dp(6), dp(4));

        FlowIconView icon = new FlowIconView(this, iconType);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        iconLp.bottomMargin = dp(8);
        panel.addView(icon, iconLp);

        TextView t = new TextView(this);
        t.setTag("panel_title");
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        panel.addView(t);

        return panel;
    }

    private View buildFlowArrow() {
        TextView arrow = new TextView(this);
        arrow.setText("\u2192");
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        arrow.setTypeface(Typeface.DEFAULT_BOLD);
        arrow.setTextColor(0xFF38BDF8);
        arrow.setGravity(Gravity.CENTER);
        arrow.setPadding(dp(4), 0, dp(4), dp(14));
        return arrow;
    }

    private Button makeBigStepperButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(Color.WHITE);
        b.setBackground(makeRoundedBg(0xFF1E293B, 0xFF38BDF8, 10, 1));
        b.setPadding(0, 0, 0, 0);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(68), dp(54)));
        return b;
    }

    private void adjustDelay(int delta) {
        int next = currentDelaySec + delta;
        if (next < MIN_DELAY_SEC) next = MIN_DELAY_SEC;
        if (next > MAX_DELAY_SEC) next = MAX_DELAY_SEC;
        if (next != currentDelaySec) {
            currentDelaySec = next;
            delayNumberText.setText(currentDelaySec + "s");
            updateWorkflowPanels();
            saveSetting(FILE_DELAY, String.valueOf(currentDelaySec));
        }
    }

    private String buildTimerPanelLabel() {
        if (ignoreHatch) {
            return currentDelaySec + " seconds after last door closes, ignoring hatchback";
        }
        return currentDelaySec + " seconds after last door closes";
    }

    private void updateWorkflowPanels() {
        if (stepTimerTitle != null) {
            stepTimerTitle.setText(buildTimerPanelLabel());
        }
        if (stepPhonePanel != null && stepPhoneArrow != null && stepPhoneTitle != null) {
            if (detectedPhoneType == PHONE_WIFI) {
                stepPhoneArrow.setVisibility(View.VISIBLE);
                stepPhonePanel.setVisibility(View.VISIBLE);
                stepPhoneTitle.setText("Wi-Fi Direct Phone signal gets weak");
            } else if (detectedPhoneType == PHONE_BT) {
                stepPhoneArrow.setVisibility(View.VISIBLE);
                stepPhonePanel.setVisibility(View.VISIBLE);
                stepPhoneTitle.setText("Bluetooth Phone signal gets weak");
            } else {
                stepPhoneArrow.setVisibility(View.GONE);
                stepPhonePanel.setVisibility(View.GONE);
            }
        }
    }

    private void refreshPhoneDetection() {
        int wifiConn = 0;
        String wifiRaw = readString(FILE_WIFI_STATE).trim();
        if (!wifiRaw.isEmpty()) {
            String[] parts = wifiRaw.split("\\s+");
            if (parts.length >= 1 && "1".equals(parts[0])) {
                wifiConn = 1;
            }
        }

        int btConn = "1".equals(readString(FILE_BT_CONN).trim()) ? 1 : 0;
        if (btConn == 0) {
            try {
                BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
                if (ba != null) {
                    int hfp = ba.getProfileConnectionState(BluetoothProfile.HEADSET);
                    int a2dp = ba.getProfileConnectionState(BluetoothProfile.A2DP);
                    if (hfp == 2 || a2dp == 2) {
                        btConn = 1;
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        // Priority: Wi-Fi Direct beats Bluetooth; neither -> No Phone Detected
        if (wifiConn == 1) {
            detectedPhoneType = PHONE_WIFI;
            setStatusLight(0xFF10B981, 0xFF34D399);
            phoneStatusTitle.setText("Wi-Fi Direct Phone Connected");
            phoneStatusTitle.setTextColor(0xFF34D399);
        } else if (btConn == 1) {
            detectedPhoneType = PHONE_BT;
            setStatusLight(0xFF10B981, 0xFF34D399);
            phoneStatusTitle.setText("Bluetooth Phone Connected");
            phoneStatusTitle.setTextColor(0xFF34D399);
        } else {
            detectedPhoneType = PHONE_NONE;
            setStatusLight(0xFFEF4444, 0xFFFCA5A5);
            phoneStatusTitle.setText("No Phone Detected, Timer only");
            phoneStatusTitle.setTextColor(0xFFFCA5A5);
        }
        updateWorkflowPanels();
    }

    private void setStatusLight(int fill, int stroke) {
        if (phoneLight == null) return;
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(fill);
        gd.setStroke(dp(2), stroke);
        phoneLight.setBackground(gd);
    }

    private void updateMasterBadge(boolean on) {
        if (on) {
            masterBadge.setText("ON");
            masterBadge.setTextColor(0xFF34D399);
            masterBadge.setBackground(makeRoundedBg(0xFF064E3B, 0xFF10B981, 6, 1));
        } else {
            masterBadge.setText("OFF");
            masterBadge.setTextColor(0xFF94A3B8);
            masterBadge.setBackground(makeRoundedBg(0xFF1E293B, 0xFF334155, 6, 1));
        }
    }

    private void loadConfigToUi() {
        updatingUi = true;
        try {
            boolean enabled = "1".equals(readString(FILE_ENABLED).trim());
            masterSwitch.setChecked(enabled);
            updateMasterBadge(enabled);

            String hatchStr = readString(FILE_IGNORE_HATCH).trim();
            ignoreHatch = hatchStr.isEmpty() || "1".equals(hatchStr);
            ignoreHatchCheck.setChecked(ignoreHatch);

            int del = DEFAULT_DELAY_SEC;
            String delStr = readString(FILE_DELAY).trim();
            try {
                if (!delStr.isEmpty()) {
                    del = Integer.parseInt(delStr);
                }
            } catch (NumberFormatException ignored) {
            }
            if (del < MIN_DELAY_SEC) del = MIN_DELAY_SEC;
            if (del > MAX_DELAY_SEC) del = MAX_DELAY_SEC;
            currentDelaySec = del;
            delayNumberText.setText(currentDelaySec + "s");
            updateWorkflowPanels();
        } finally {
            updatingUi = false;
        }
    }

    private void saveSetting(String path, String value) {
        if (writeString(path, value) && savedBadge != null) {
            savedBadge.setVisibility(View.VISIBLE);
            handler.removeCallbacks(hideSavedBadge);
            handler.postDelayed(hideSavedBadge, 1800);
        }
    }

    private GradientDrawable makeRoundedBg(int fillColor, int strokeColor, int radiusDp, int strokeDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fillColor);
        gd.setCornerRadius(dp(radiusDp));
        gd.setStroke(dp(strokeDp), strokeColor);
        return gd;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static boolean writeString(String path, String content) {
        try (FileWriter fw = new FileWriter(path, false)) {
            fw.write(content);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String readString(String path) {
        File f = new File(path);
        if (!f.exists()) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    /** Custom Canvas-drawn icons for API 19 compatibility. */
    private static class FlowIconView extends View {
        static final int ICON_IGNITION = 1;
        static final int ICON_TIMER = 2;
        static final int ICON_SIGNAL = 3;
        static final int ICON_LOCK = 4;
        static final int ICON_WARNING = 5;

        private final int type;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path path = new Path();

        FlowIconView(Context ctx, int type) {
            super(ctx);
            this.type = type;
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w = getWidth();
            float h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            float s = Math.min(w, h) / 100f;

            if (type == ICON_WARNING) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(7f * s);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setColor(0xFFFBBF24);
                path.reset();
                path.moveTo(cx, 14f * s);
                path.lineTo(88f * s, 84f * s);
                path.lineTo(12f * s, 84f * s);
                path.close();
                c.drawPath(path, paint);

                paint.setStyle(Paint.Style.FILL);
                c.drawRoundRect(new RectF(cx - 4f * s, 38f * s, cx + 4f * s, 64f * s), 4f * s, 4f * s, paint);
                c.drawCircle(cx, 74f * s, 4.5f * s, paint);
                return;
            }

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF0F172A);
            c.drawCircle(cx, cy, 46f * s, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3f * s);
            paint.setColor(0xFF38BDF8);
            c.drawCircle(cx, cy, 46f * s, paint);

            if (type == ICON_IGNITION) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(6f * s);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setColor(0xFF7DD3FC);
                rect.set(cx - 20f * s, cy - 20f * s, cx + 20f * s, cy + 20f * s);
                c.drawArc(rect, -55, 290, false, paint);
                c.drawLine(cx, cy - 25f * s, cx, cy - 4f * s, paint);
            } else if (type == ICON_TIMER) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(5f * s);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setColor(0xFF22D3EE);
                c.drawCircle(cx, cy, 22f * s, paint);
                paint.setColor(Color.WHITE);
                c.drawLine(cx, cy, cx, cy - 13f * s, paint);
                paint.setColor(0xFFFBBF24);
                c.drawLine(cx, cy, cx + 11f * s, cy + 5f * s, paint);
            } else if (type == ICON_SIGNAL) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(5f * s);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setColor(0xFF34D399);
                rect.set(cx - 24f * s, cy - 14f * s, cx + 24f * s, cy + 34f * s);
                c.drawArc(rect, 225, 90, false, paint);
                rect.set(cx - 14f * s, cy - 2f * s, cx + 14f * s, cy + 26f * s);
                c.drawArc(rect, 225, 90, false, paint);
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy + 18f * s, 4.5f * s, paint);
            } else if (type == ICON_LOCK) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(5.5f * s);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setColor(0xFF7DD3FC);
                rect.set(cx - 13f * s, 23f * s, cx + 13f * s, 50f * s);
                c.drawArc(rect, 180, 180, false, paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFF1E3A5F);
                rect.set(29f * s, 41f * s, 71f * s, 74f * s);
                c.drawRoundRect(rect, 7f * s, 7f * s, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(3f * s);
                paint.setColor(0xFF38BDF8);
                c.drawRoundRect(rect, 7f * s, 7f * s, paint);
            }
        }
    }
}
