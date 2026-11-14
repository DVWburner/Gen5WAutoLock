package com.gen5w.autolock;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Background bridge service that keeps BCM door state and Bluetooth connection
 * state updated in /data/nemesis/autolock/ even when MainActivity is closed.
 */
public class AutoLockBridgeService extends Service {
    public static final String BASE_DIR = "/data/nemesis/autolock";
    public static final String FILE_DOOR = BASE_DIR + "/door_state";
    public static final String FILE_BT_CONN = BASE_DIR + "/bt_connected";
    public static final String FILE_LOG = BASE_DIR + "/autolock.log";

    private IBinder bcmService;
    private Binder bcmListener;
    private Binder bcmToken;

    private final Handler handler = new Handler();
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (bcmListener == null) {
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
        if (bcmListener == null) {
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
                            data.readInt(); // drvUnlock
                            data.readInt(); // pasUnlock
                            data.readInt(); // rlUnlock
                            data.readInt(); // rrUnlock
                            int drvOpen = data.readInt();
                            int pasOpen = data.readInt();
                            int rlOpen = data.readInt();
                            int rrOpen = data.readInt();
                            data.readInt(); // trunkOpen (hatch excluded)
                            data.readInt(); // hoodState
                            int anyCabinOpen = (drvOpen == 1 || pasOpen == 1 || rlOpen == 1 || rrOpen == 1) ? 1 : 0;
                            int allCabinClosed = (anyCabinOpen == 0) ? 1 : 0;
                            writeString(FILE_DOOR, anyCabinOpen + " " + allCabinClosed + "\n");
                        }
                        if (reply != null) reply.writeNoException();
                        return true;
                    } catch (Throwable t) {
                        appendLog("BCM_CB_ERR code=" + code + ": " + t.getMessage());
                        return super.onTransact(code, data, reply, flags);
                    }
                }
            };
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
            appendLog("BRIDGE_SVC_ERR: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            bcmListener = null;
        }
    }

    private void unregisterBcmListener() {
        if (bcmService == null || bcmListener == null) return;
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

    private void updateBluetoothState() {
        try {
            BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
            if (ba == null) {
                writeString(FILE_BT_CONN, "0");
                return;
            }
            int hfp = ba.getProfileConnectionState(BluetoothProfile.HEADSET);
            int a2dp = ba.getProfileConnectionState(BluetoothProfile.A2DP);
            writeString(FILE_BT_CONN, (hfp == 2 || a2dp == 2) ? "1" : "0");
        } catch (Throwable ignored) {
        }
    }

    private static void writeString(String path, String content) {
        try (FileWriter fw = new FileWriter(path, false)) {
            fw.write(content);
        } catch (Throwable ignored) {
        }
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
