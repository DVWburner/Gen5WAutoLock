package com.gen5w.autolock;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

/**
 * Background bridge service that keeps BCM door state and Bluetooth connection
 * state updated in /data/nemesis/autolock/ even when MainActivity is closed.
 */
public class AutoLockBridgeService extends Service {
    public static final String BASE_DIR = "/data/nemesis/autolock";
    public static final String FILE_DOOR = BASE_DIR + "/door_state";
    public static final String FILE_LOCK_STATE = BASE_DIR + "/lock_state";
    public static final String FILE_BT_CONN = BASE_DIR + "/bt_connected";
    public static final String FILE_LOG = BASE_DIR + "/autolock.log";

    private static final int PROFILE_A2DP_SINK = 11;
    private static final int PROFILE_HEADSET_CLIENT = 16;

    private IBinder bcmService;
    private Binder bcmListener;
    private Binder bcmToken;
    private IBinder.DeathRecipient bcmDeathRecipient;
    private int lastAnyCabinOpen = -1;
    private int lastTrunkOpen = -1;
    private int cabinEventSeq = 0;
    private int allDoorEventSeq = 0;
    private static String lastErrMsg = "";
    private static long lastErrTimeMs = 0L;

    private final Handler handler = new Handler();
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (bcmListener == null || bcmService == null || !bcmService.isBinderAlive()) {
                bcmListener = null;
                bcmService = null;
                registerBcmListener();
            }
            updateBluetoothState();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        registerBcmListener();
        handler.post(pollRunnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (bcmListener == null || bcmService == null || !bcmService.isBinderAlive()) {
            bcmListener = null;
            bcmService = null;
            registerBcmListener();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(pollRunnable);
        unregisterBcmListener();
        super.onDestroy();
    }

    private void registerBcmListener() {
        if (bcmListener != null) return;
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            bcmService = (IBinder) sm.getMethod("getService", String.class)
                    .invoke(null, "com.hkmc.micom.bcm");
            if (bcmService == null) {
                return;
            }
            bcmToken = new Binder();
            bcmListener = new Binder() {
                @Override
                protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                        throws RemoteException {
                    if (code == INTERFACE_TRANSACTION) {
                        reply.writeString("com.hkmc.bcm.IBCMListener");
                        return true;
                    }
                    try {
                        data.enforceInterface("com.hkmc.bcm.IBCMListener");
                        if (code == 2) { // onDoorAllStateChanged(10 ints)
                            int drvUnlock = data.readInt(); // 0 = locked, 1 = unlocked
                            int pasUnlock = data.readInt();
                            int rlUnlock = data.readInt();
                            int rrUnlock = data.readInt();
                            int drvOpen = data.readInt();
                            int pasOpen = data.readInt();
                            int rlOpen = data.readInt();
                            int rrOpen = data.readInt();
                            int trunkOpen = data.readInt();
                            data.readInt(); // hoodState
                            int allLocked = (drvUnlock == 0 && pasUnlock == 0
                                    && rlUnlock == 0 && rrUnlock == 0) ? 1 : 0;
                            int anyCabinOpen = (drvOpen == 1 || pasOpen == 1 || rlOpen == 1 || rrOpen == 1) ? 1 : 0;
                            int allCabinClosed = (anyCabinOpen == 0) ? 1 : 0;
                            if (lastAnyCabinOpen != -1) {
                                if (anyCabinOpen != lastAnyCabinOpen) {
                                    cabinEventSeq++;
                                    allDoorEventSeq++;
                                } else if (trunkOpen != lastTrunkOpen) {
                                    allDoorEventSeq++;
                                }
                            }
                            lastAnyCabinOpen = anyCabinOpen;
                            lastTrunkOpen = trunkOpen;
                            writeString(FILE_DOOR, anyCabinOpen + " " + allCabinClosed + " "
                                    + trunkOpen + " " + cabinEventSeq + " " + allDoorEventSeq + "\n");
                            writeString(FILE_LOCK_STATE, allLocked + " " + drvUnlock + " " + pasUnlock
                                    + " " + rlUnlock + " " + rrUnlock + "\n");
                        }
                        if (reply != null) reply.writeNoException();
                        return true;
                    } catch (Throwable t) {
                        appendLogThrottled("BCM_CB_ERR code=" + code + ": " + t.getMessage());
                        return super.onTransact(code, data, reply, flags);
                    }
                }
            };
            bcmDeathRecipient = new IBinder.DeathRecipient() {
                @Override
                public void binderDied() {
                    handler.post(() -> {
                        bcmListener = null;
                        bcmService = null;
                        appendLogThrottled("BRIDGE_SVC: BCM binder died; scheduling reconnect");
                    });
                }
            };
            try {
                bcmService.linkToDeath(bcmDeathRecipient, 0);
            } catch (Throwable ignored) {
            }
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken("com.hkmc.bcm.IBCMService");
                data.writeStrongBinder(bcmListener);
                data.writeStrongBinder(bcmToken);
                bcmService.transact(1, data, reply, 0);
                reply.readException();
                appendLog("BRIDGE_SVC: BCM listener registered");
            } finally {
                reply.recycle();
                data.recycle();
            }
        } catch (Throwable t) {
            appendLogThrottled("BRIDGE_SVC_ERR: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            bcmListener = null;
        }
    }

    private void unregisterBcmListener() {
        if (bcmService == null || bcmListener == null) return;
        try {
            if (bcmDeathRecipient != null) {
                bcmService.unlinkToDeath(bcmDeathRecipient, 0);
            }
        } catch (Throwable ignored) {
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("com.hkmc.bcm.IBCMService");
            data.writeStrongBinder(bcmListener);
            data.writeStrongBinder(bcmToken);
            bcmService.transact(2, data, reply, 0);
            reply.readException();
        } catch (Throwable ignored) {
        } finally {
            reply.recycle();
            data.recycle();
            bcmListener = null;
        }
    }

    public static boolean isBluetoothPhoneConnected() {
        try {
            BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
            if (ba == null || !ba.isEnabled()) {
                return false;
            }
            if (ba.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED
                    || ba.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED
                    || ba.getProfileConnectionState(PROFILE_HEADSET_CLIENT) == BluetoothProfile.STATE_CONNECTED
                    || ba.getProfileConnectionState(PROFILE_A2DP_SINK) == BluetoothProfile.STATE_CONNECTED) {
                return true;
            }
            Set<BluetoothDevice> bonded = ba.getBondedDevices();
            if (bonded != null && !bonded.isEmpty()) {
                Method isConnMethod = BluetoothDevice.class.getMethod("isConnected");
                for (BluetoothDevice dev : bonded) {
                    BluetoothClass btClass = dev.getBluetoothClass();
                    if (btClass != null
                            && btClass.getMajorDeviceClass() == BluetoothClass.Device.Major.PHONE) {
                        Object res = isConnMethod.invoke(dev);
                        if (Boolean.TRUE.equals(res)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void updateBluetoothState() {
        writeString(FILE_BT_CONN, isBluetoothPhoneConnected() ? "1" : "0");
    }

    private static void writeString(String path, String content) {
        File target = new File(path);
        File tmp = new File(path + ".tmp");
        try {
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileWriter fw = new FileWriter(tmp, false)) {
                fw.write(content);
            }
            tmp.setReadable(true, false);
            tmp.setWritable(true, false);
            if (tmp.renameTo(target)) {
                target.setReadable(true, false);
                target.setWritable(true, false);
                return;
            }
            tmp.delete();
        } catch (Throwable ignored) {
        }
        try (FileWriter fw = new FileWriter(target, false)) {
            fw.write(content);
            target.setReadable(true, false);
            target.setWritable(true, false);
        } catch (Throwable ignored) {
        }
    }

    private static synchronized void appendLogThrottled(String msg) {
        long now = System.currentTimeMillis();
        if (msg.equals(lastErrMsg) && (now - lastErrTimeMs) < 60000L) {
            return;
        }
        lastErrMsg = msg;
        lastErrTimeMs = now;
        appendLog(msg);
    }

    private static synchronized void appendLog(String msg) {
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        String line = "[" + ts + "] " + msg + "\n";
        try (FileWriter fw = new FileWriter(FILE_LOG, true)) {
            fw.write(line);
        } catch (Throwable ignored) {
        }
    }
}
