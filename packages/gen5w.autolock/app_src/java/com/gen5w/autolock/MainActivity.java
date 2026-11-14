package com.gen5w.autolock;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

public class MainActivity extends Activity {
    public static final String APP_VERSION = "v1.0.0";

    private static final String BASE_DIR = "/data/nemesis/autolock";
    private static final String FILE_ENABLED = BASE_DIR + "/enabled";
    private static final String FILE_DELAY = BASE_DIR + "/delay";
    private static final String FILE_BT_REQ = BASE_DIR + "/bt_req";

    private static final int MIN_DELAY_SEC = 5;
    private static final int MAX_DELAY_SEC = 20;
    private static final int DEFAULT_DELAY_SEC = 10;
    private static final int DISCLAIMER_WAIT_SEC = 5;

    private final Handler handler = new Handler();
    private boolean updatingUi = false;
    private int currentDelaySec = DEFAULT_DELAY_SEC;

    private FrameLayout disclaimerOverlay;
    private LinearLayout mainContainer;
    private Button understandBtn;
    private int countdownRemaining = DISCLAIMER_WAIT_SEC;

    private Switch masterSwitch;
    private TextView masterBadge;
    private SeekBar delaySeekBar;
    private TextView delayValueBadge;
    private TextView savedBadge;
    private TextView stepLockTitle;
    private Switch btReqSwitch;

    private final Runnable hideSavedBadge = new Runnable() {
        @Override
        public void run() {
            if (savedBadge != null) {
                savedBadge.setVisibility(View.INVISIBLE);
            }
        }
    };

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep background BCM & Bluetooth bridge active even after Exit is pressed
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
        handler.postDelayed(disclaimerTick, 1000);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(disclaimerTick);
        handler.removeCallbacks(hideSavedBadge);
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

        // Warning header
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

        // Main warning message
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

        // Action buttons
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

        // 1. Header Bar
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
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
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
        savedBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        savedBadge.setTypeface(Typeface.DEFAULT_BOLD);
        savedBadge.setTextColor(0xFF34D399);
        savedBadge.setBackground(makeRoundedBg(0xFF064E3B, 0xFF10B981, 8, 1));
        savedBadge.setPadding(dp(12), dp(5), dp(12), dp(5));
        savedBadge.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams savedLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        savedLp.rightMargin = dp(12);
        header.addView(savedBadge, savedLp);

        Button exitBtn = new Button(this);
        exitBtn.setText("Exit");
        exitBtn.setAllCaps(false);
        exitBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        exitBtn.setTypeface(Typeface.DEFAULT_BOLD);
        exitBtn.setTextColor(0xFFE2E8F0);
        exitBtn.setBackground(makeRoundedBg(0xFF1E293B, 0xFF334155, 8, 1));
        exitBtn.setPadding(dp(22), dp(8), dp(22), dp(8));
        exitBtn.setOnClickListener(v -> finish());
        header.addView(exitBtn);

        container.addView(header);

        // 2. Visual Flow Card
        LinearLayout flowCard = new LinearLayout(this);
        flowCard.setOrientation(LinearLayout.VERTICAL);
        flowCard.setBackground(makeRoundedBg(0xFF161B22, 0xFF263040, 14, 1));
        flowCard.setPadding(dp(20), dp(14), dp(20), dp(12));

        LinearLayout stepsRow = new LinearLayout(this);
        stepsRow.setOrientation(LinearLayout.HORIZONTAL);
        stepsRow.setGravity(Gravity.CENTER_VERTICAL);

        stepsRow.addView(buildFlowStep(FlowIconView.ICON_IGNITION, "Ignition Off", "Park & power down"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        stepsRow.addView(buildFlowArrow());
        stepsRow.addView(buildFlowStep(FlowIconView.ICON_EXIT_CAR, "Driver Exits Car", "Cabin doors close"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        stepsRow.addView(buildFlowArrow());

        LinearLayout lockStep = buildFlowStep(
                FlowIconView.ICON_LOCK_TIMER,
                "Doors Lock " + currentDelaySec + "s After Last Door Closes",
                "All 4 cabin doors secured");
        stepLockTitle = (TextView) lockStep.findViewWithTag("step_title");
        stepsRow.addView(lockStep, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f));

        flowCard.addView(stepsRow);

        // Hatch exclusion line
        LinearLayout hatchNoteRow = new LinearLayout(this);
        hatchNoteRow.setOrientation(LinearLayout.HORIZONTAL);
        hatchNoteRow.setGravity(Gravity.CENTER);
        hatchNoteRow.setPadding(dp(12), dp(6), dp(12), dp(6));
        hatchNoteRow.setBackground(makeRoundedBg(0xFF111827, 0xFF1F2937, 8, 1));

        TextView hatchNote = new TextView(this);
        hatchNote.setText("Note: The rear hatch is not included in this timer.");
        hatchNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hatchNote.setTextColor(0xFF94A3B8);
        hatchNoteRow.addView(hatchNote);

        LinearLayout.LayoutParams hatchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hatchLp.topMargin = dp(10);
        flowCard.addView(hatchNoteRow, hatchLp);

        LinearLayout.LayoutParams flowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flowLp.bottomMargin = dp(12);
        container.addView(flowCard, flowLp);

        // 3. Controls Card
        LinearLayout controlsCard = new LinearLayout(this);
        controlsCard.setOrientation(LinearLayout.VERTICAL);
        controlsCard.setBackground(makeRoundedBg(0xFF161B22, 0xFF263040, 14, 1));
        controlsCard.setPadding(dp(22), dp(14), dp(22), dp(14));

        // Master On/Off Row
        LinearLayout masterRow = new LinearLayout(this);
        masterRow.setOrientation(LinearLayout.HORIZONTAL);
        masterRow.setGravity(Gravity.CENTER_VERTICAL);
        masterRow.setPadding(0, dp(4), 0, dp(10));

        LinearLayout masterLabels = new LinearLayout(this);
        masterLabels.setOrientation(LinearLayout.VERTICAL);
        TextView masterTitle = new TextView(this);
        masterTitle.setText("Timed Auto-Lock");
        masterTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        masterTitle.setTypeface(Typeface.DEFAULT_BOLD);
        masterTitle.setTextColor(Color.WHITE);
        masterLabels.addView(masterTitle);

        TextView masterSub = new TextView(this);
        masterSub.setText("Master On / Off for automatic walk-away locking");
        masterSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        masterSub.setTextColor(0xFF94A3B8);
        masterLabels.addView(masterSub);
        masterRow.addView(masterLabels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        masterBadge = new TextView(this);
        masterBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        masterBadge.setTypeface(Typeface.DEFAULT_BOLD);
        masterBadge.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeLp.rightMargin = dp(12);
        masterRow.addView(masterBadge, badgeLp);

        masterSwitch = new Switch(this);
        masterSwitch.setOnCheckedChangeListener((btn, checked) -> {
            updateMasterBadge(checked);
            if (!updatingUi) {
                saveSetting(FILE_ENABLED, checked ? "1" : "0");
            }
        });
        masterRow.addView(masterSwitch);
        controlsCard.addView(masterRow);

        controlsCard.addView(makeDivider());

        // Timeout Slider Row (5-20s)
        LinearLayout sliderSection = new LinearLayout(this);
        sliderSection.setOrientation(LinearLayout.VERTICAL);
        sliderSection.setPadding(0, dp(10), 0, dp(10));

        LinearLayout sliderHeader = new LinearLayout(this);
        sliderHeader.setOrientation(LinearLayout.HORIZONTAL);
        sliderHeader.setGravity(Gravity.CENTER_VERTICAL);

        TextView sliderTitle = new TextView(this);
        sliderTitle.setText("Lock Delay Timer");
        sliderTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        sliderTitle.setTypeface(Typeface.DEFAULT_BOLD);
        sliderTitle.setTextColor(Color.WHITE);
        sliderHeader.addView(sliderTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        delayValueBadge = new TextView(this);
        delayValueBadge.setText(currentDelaySec + "s");
        delayValueBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        delayValueBadge.setTypeface(Typeface.DEFAULT_BOLD);
        delayValueBadge.setTextColor(0xFF38BDF8);
        delayValueBadge.setBackground(makeRoundedBg(0xFF0F172A, 0xFF1E3A5F, 8, 1));
        delayValueBadge.setPadding(dp(14), dp(4), dp(14), dp(4));
        sliderHeader.addView(delayValueBadge);
        sliderSection.addView(sliderHeader);

        LinearLayout sliderRow = new LinearLayout(this);
        sliderRow.setOrientation(LinearLayout.HORIZONTAL);
        sliderRow.setGravity(Gravity.CENTER_VERTICAL);
        sliderRow.setPadding(0, dp(8), 0, 0);

        Button minusBtn = makeStepperButton("-");
        minusBtn.setOnClickListener(v -> adjustDelay(-1));
        sliderRow.addView(minusBtn);

        TextView minLbl = new TextView(this);
        minLbl.setText(MIN_DELAY_SEC + "s");
        minLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        minLbl.setTextColor(0xFF94A3B8);
        LinearLayout.LayoutParams minLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        minLp.leftMargin = dp(10);
        sliderRow.addView(minLbl, minLp);

        delaySeekBar = buildStyledSeekBar();
        delaySeekBar.setMax(MAX_DELAY_SEC - MIN_DELAY_SEC);
        delaySeekBar.setProgress(currentDelaySec - MIN_DELAY_SEC);
        delaySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentDelaySec = MIN_DELAY_SEC + progress;
                updateDelayTexts();
                if (fromUser && !updatingUi) {
                    saveSetting(FILE_DELAY, String.valueOf(currentDelaySec));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (!updatingUi) {
                    saveSetting(FILE_DELAY, String.valueOf(currentDelaySec));
                }
            }
        });
        LinearLayout.LayoutParams seekLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        seekLp.leftMargin = dp(10);
        seekLp.rightMargin = dp(10);
        sliderRow.addView(delaySeekBar, seekLp);

        TextView maxLbl = new TextView(this);
        maxLbl.setText(MAX_DELAY_SEC + "s");
        maxLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        maxLbl.setTextColor(0xFF94A3B8);
        LinearLayout.LayoutParams maxLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        maxLp.rightMargin = dp(10);
        sliderRow.addView(maxLbl, maxLp);

        Button plusBtn = makeStepperButton("+");
        plusBtn.setOnClickListener(v -> adjustDelay(1));
        sliderRow.addView(plusBtn);

        sliderSection.addView(sliderRow);
        controlsCard.addView(sliderSection);

        controlsCard.addView(makeDivider());

        // Bluetooth Requirement Row
        LinearLayout btRow = new LinearLayout(this);
        btRow.setOrientation(LinearLayout.HORIZONTAL);
        btRow.setGravity(Gravity.CENTER_VERTICAL);
        btRow.setPadding(0, dp(10), 0, dp(2));

        LinearLayout btLabels = new LinearLayout(this);
        btLabels.setOrientation(LinearLayout.VERTICAL);
        TextView btTitle = new TextView(this);
        btTitle.setText("Require Bluetooth connection to phone to drop before locking");
        btTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        btTitle.setTypeface(Typeface.DEFAULT_BOLD);
        btTitle.setTextColor(Color.WHITE);
        btLabels.addView(btTitle);

        TextView btSub = new TextView(this);
        btSub.setText("Waits until your phone disconnects from car Bluetooth after the timer finishes");
        btSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        btSub.setTextColor(0xFF94A3B8);
        btLabels.addView(btSub);
        btRow.addView(btLabels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        btReqSwitch = new Switch(this);
        btReqSwitch.setOnCheckedChangeListener((btn, checked) -> {
            if (!updatingUi) {
                saveSetting(FILE_BT_REQ, checked ? "1" : "0");
            }
        });
        btRow.addView(btReqSwitch);
        controlsCard.addView(btRow);

        container.addView(controlsCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

        return container;
    }

    private LinearLayout buildFlowStep(int iconType, String title, String subtitle) {
        LinearLayout step = new LinearLayout(this);
        step.setOrientation(LinearLayout.VERTICAL);
        step.setGravity(Gravity.CENTER_HORIZONTAL);
        step.setPadding(dp(8), dp(4), dp(8), dp(4));

        FlowIconView icon = new FlowIconView(this, iconType);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(46), dp(46));
        iconLp.bottomMargin = dp(8);
        step.addView(icon, iconLp);

        TextView t = new TextView(this);
        t.setTag("step_title");
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        step.addView(t);

        TextView s = new TextView(this);
        s.setText(subtitle);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        s.setTextColor(0xFF94A3B8);
        s.setGravity(Gravity.CENTER);
        step.addView(s);

        return step;
    }

    private View buildFlowArrow() {
        TextView arrow = new TextView(this);
        arrow.setText("\u2192");
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        arrow.setTypeface(Typeface.DEFAULT_BOLD);
        arrow.setTextColor(0xFF38BDF8);
        arrow.setGravity(Gravity.CENTER);
        arrow.setPadding(dp(6), 0, dp(6), dp(18));
        return arrow;
    }

    private View makeDivider() {
        View v = new View(this);
        v.setBackgroundColor(0xFF263040);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        v.setLayoutParams(lp);
        return v;
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

    private void updateDelayTexts() {
        if (delayValueBadge != null) {
            delayValueBadge.setText(currentDelaySec + "s");
        }
        if (stepLockTitle != null) {
            stepLockTitle.setText("Doors Lock " + currentDelaySec + "s After Last Door Closes");
        }
    }

    private void loadConfigToUi() {
        updatingUi = true;
        try {
            boolean enabled = "1".equals(readString(FILE_ENABLED).trim());
            masterSwitch.setChecked(enabled);
            updateMasterBadge(enabled);

            boolean btReq = "1".equals(readString(FILE_BT_REQ).trim());
            btReqSwitch.setChecked(btReq);

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
            delaySeekBar.setProgress(currentDelaySec - MIN_DELAY_SEC);
            updateDelayTexts();
        } finally {
            updatingUi = false;
        }
    }

    private GradientDrawable makeRoundedBg(int fillColor, int strokeColor, int radiusDp, int strokeDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fillColor);
        gd.setCornerRadius(dp(radiusDp));
        gd.setStroke(dp(strokeDp), strokeColor);
        return gd;
    }

    private SeekBar buildStyledSeekBar() {
        SeekBar sb = new SeekBar(this);
        sb.setPadding(dp(14), dp(8), dp(14), dp(8));

        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setColor(0xFF334155);
        trackBg.setCornerRadius(dp(6));
        trackBg.setSize(dp(10), dp(10));

        GradientDrawable trackFill = new GradientDrawable();
        trackFill.setColor(0xFF38BDF8);
        trackFill.setCornerRadius(dp(6));
        trackFill.setSize(dp(10), dp(10));

        ClipDrawable progressClip = new ClipDrawable(trackFill, Gravity.LEFT, ClipDrawable.HORIZONTAL);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{trackBg, progressClip});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        sb.setProgressDrawable(layers);

        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(Color.WHITE);
        thumb.setStroke(dp(3), 0xFF38BDF8);
        thumb.setSize(dp(26), dp(26));
        sb.setThumb(thumb);
        sb.setThumbOffset(dp(13));
        return sb;
    }

    private Button makeStepperButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(Color.WHITE);
        b.setBackground(makeRoundedBg(0xFF1E293B, 0xFF38BDF8, 8, 1));
        b.setPadding(0, 0, 0, 0);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(36)));
        return b;
    }

    private void adjustDelay(int delta) {
        int next = currentDelaySec + delta;
        if (next < MIN_DELAY_SEC) next = MIN_DELAY_SEC;
        if (next > MAX_DELAY_SEC) next = MAX_DELAY_SEC;
        if (next != currentDelaySec) {
            currentDelaySec = next;
            delaySeekBar.setProgress(currentDelaySec - MIN_DELAY_SEC);
            updateDelayTexts();
            saveSetting(FILE_DELAY, String.valueOf(currentDelaySec));
        }
    }

    private void saveSetting(String path, String value) {
        if (writeString(path, value) && savedBadge != null) {
            savedBadge.setVisibility(View.VISIBLE);
            handler.removeCallbacks(hideSavedBadge);
            handler.postDelayed(hideSavedBadge, 1800);
        }
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

    /** Custom Canvas-drawn crisp icons for API 19 compatibility. */
    private static class FlowIconView extends View {
        static final int ICON_IGNITION = 1;
        static final int ICON_EXIT_CAR = 2;
        static final int ICON_LOCK_TIMER = 3;
        static final int ICON_WARNING = 4;

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

            // Circular badge background
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF0F172A);
            c.drawCircle(cx, cy, 46f * s, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3f * s);
            paint.setColor(0xFF38BDF8);
            c.drawCircle(cx, cy, 46f * s, paint);

            if (type == ICON_IGNITION) {
                // Power symbol
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(6.5f * s);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setColor(0xFF7DD3FC);
                rect.set(cx - 21f * s, cy - 21f * s, cx + 21f * s, cy + 21f * s);
                c.drawArc(rect, -55, 290, false, paint);
                c.drawLine(cx, cy - 26f * s, cx, cy - 4f * s, paint);
            } else if (type == ICON_EXIT_CAR) {
                // Door frame + exit arrow
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(5.5f * s);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setColor(0xFF7DD3FC);
                rect.set(27f * s, 26f * s, 57f * s, 74f * s);
                c.drawRoundRect(rect, 4f * s, 4f * s, paint);

                // Arrow pointing out right
                paint.setColor(0xFF34D399);
                c.drawLine(46f * s, cy, 76f * s, cy, paint);
                c.drawLine(66f * s, cy - 10f * s, 76f * s, cy, paint);
                c.drawLine(66f * s, cy + 10f * s, 76f * s, cy, paint);
            } else if (type == ICON_LOCK_TIMER) {
                // Padlock + clock hands
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(5.5f * s);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setColor(0xFF7DD3FC);
                rect.set(cx - 14f * s, 22f * s, cx + 14f * s, 52f * s);
                c.drawArc(rect, 180, 180, false, paint);

                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFF1E3A5F);
                rect.set(27f * s, 40f * s, 73f * s, 76f * s);
                c.drawRoundRect(rect, 8f * s, 8f * s, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(3f * s);
                paint.setColor(0xFF38BDF8);
                c.drawRoundRect(rect, 8f * s, 8f * s, paint);

                // Clock circle inside lock
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2.8f * s);
                paint.setColor(0xFF22D3EE);
                c.drawCircle(cx, 58f * s, 11f * s, paint);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setColor(Color.WHITE);
                c.drawLine(cx, 58f * s, cx, 51f * s, paint);
                paint.setColor(0xFFFBBF24);
                c.drawLine(cx, 58f * s, cx + 6f * s, 61f * s, paint);
            }
        }
    }
}
