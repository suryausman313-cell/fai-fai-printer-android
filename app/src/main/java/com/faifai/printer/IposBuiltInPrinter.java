package com.faifai.printer;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Built-in printer bridge for the Newpas Q2I.
 *
 * Important: receipt DESIGN is not created here. NetworkReceiptPrinter renders
 * the same ESC/POS bytes as the old receipt, then this class sends those exact
 * bytes to the Q2I iPos built-in printer service.
 */
final class IposBuiltInPrinter {
    private static final String SERVICE_PACKAGE = "com.iposprinter.iposprinterservice";
    private static final String SERVICE_ACTION = "com.iposprinter.iposprinterservice.IPosPrintService";
    private static final String SERVICE_DESCRIPTOR = "com.iposprinter.iposprinterservice.IPosPrinterService";
    private static final String CALLBACK_DESCRIPTOR = "com.iposprinter.iposprinterservice.IPosPrinterCallback";

    // IPosPrinterService AIDL transaction order.
    private static final int TX_GET_STATUS = 1;
    private static final int TX_PRINTER_INIT = 2;
    private static final int TX_PRINT_RAW_DATA = 16; // vendor raw-byte API (kept documented; not used for ESC/POS receipt)
    private static final int TX_SEND_USER_CMD_DATA = 17; // vendor ESC/POS command API
    private static final int TX_PERFORM_PRINT = 18;

    private final Context appContext;
    private final Object lock = new Object();
    private volatile IBinder service;
    private volatile boolean binding;
    private CountDownLatch bindLatch = new CountDownLatch(1);
    private final CallbackBinder callback = new CallbackBinder();

    IposBuiltInPrinter(Context context) {
        appContext = context.getApplicationContext();
    }

    void bind() {
        synchronized (lock) {
            if (service != null || binding) return;
            binding = true;
            bindLatch = new CountDownLatch(1);
        }

        Intent intent = new Intent(SERVICE_ACTION);
        intent.setPackage(SERVICE_PACKAGE);
        try {
            boolean ok = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!ok) {
                synchronized (lock) {
                    binding = false;
                    bindLatch.countDown();
                }
            }
        } catch (Exception ignored) {
            synchronized (lock) {
                binding = false;
                bindLatch.countDown();
            }
        }
    }

    void unbind() {
        try {
            appContext.unbindService(connection);
        } catch (Exception ignored) {
        }
        service = null;
        binding = false;
    }

    void printReceipt(String payloadJson) throws Exception {
        if (payloadJson == null || payloadJson.trim().isEmpty()) {
            throw new IllegalArgumentException("Receipt data is empty");
        }

        ensureConnected();
        waitUntilReady();

        // Reuse the OLD receipt renderer exactly; only the transport changes
        // from network socket to the Q2I's built-in iPos printer.
        byte[] receiptBytes = NetworkReceiptPrinter.renderForBuiltIn(appContext, payloadJson);
        if (receiptBytes == null || receiptBytes.length == 0) {
            throw new IllegalStateException("Receipt data is empty");
        }

        transactVoid(TX_PRINTER_INIT, data -> data.writeStrongBinder(callback));

        // The V1.7 renderer produces a complete ESC/POS byte stream (font size,
        // bold, alignment, spacing and raster logo commands included). The Q2I
        // vendor AIDL exposes transaction 17 specifically for ESC/POS commands,
        // so send the whole old receipt through that path to preserve its layout.
        transactVoid(TX_SEND_USER_CMD_DATA, data -> {
            data.writeByteArray(receiptBytes);
            data.writeStrongBinder(callback);
        });

        // The ESC/POS receipt already contains its own final paper feed, so do
        // not add a second large feed here. This call commits the queued print.
        transactVoid(TX_PERFORM_PRINT, data -> {
            data.writeInt(0);
            data.writeStrongBinder(callback);
        });
    }

    private void waitUntilReady() throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
            int status = getPrinterStatus();
            if (status == 0) return;
            if (status == 1) throw new IllegalStateException("Printer is out of paper");
            if (status == 2) throw new IllegalStateException("Printer head is too hot");
            if (status == 3) throw new IllegalStateException("Printer motor is too hot");
            if (status == 5) throw new IllegalStateException("Built-in printer error");
            Thread.sleep(200L);
        }
        throw new IllegalStateException("Built-in printer is busy");
    }

    private int getPrinterStatus() throws Exception {
        IBinder target = connectedBinder();
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            boolean handled = target.transact(TX_GET_STATUS, data, reply, 0);
            if (!handled) throw new RemoteException("Printer status transaction failed");
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void transactVoid(int code, ParcelWriter writer) throws Exception {
        IBinder target = connectedBinder();
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            writer.write(data);
            boolean handled = target.transact(code, data, reply, 0);
            if (!handled) throw new RemoteException("Printer transaction " + code + " failed");
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private IBinder connectedBinder() throws Exception {
        ensureConnected();
        IBinder result = service;
        if (result == null) throw new IllegalStateException("Built-in printer service unavailable");
        return result;
    }

    private void ensureConnected() throws Exception {
        if (service != null && service.isBinderAlive()) return;
        bind();
        CountDownLatch latch = bindLatch;
        if (!latch.await(2500L, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("Built-in printer connection timed out");
        }
        if (service == null || !service.isBinderAlive()) {
            throw new IllegalStateException("Built-in printer service not found");
        }
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = binder;
            synchronized (lock) {
                binding = false;
                bindLatch.countDown();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            binding = false;
        }
    };

    private interface ParcelWriter {
        void write(Parcel data) throws Exception;
    }

    /** Minimal Binder callback compatible with IPosPrinterCallback. */
    private static final class CallbackBinder extends Binder implements IInterface {
        CallbackBinder() {
            attachInterface(this, CALLBACK_DESCRIPTOR);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(CALLBACK_DESCRIPTOR);
                return true;
            }
            if (code == 1) { // onRunResult(boolean)
                data.enforceInterface(CALLBACK_DESCRIPTOR);
                data.readInt();
                return true;
            }
            if (code == 2) { // onReturnString(String)
                data.enforceInterface(CALLBACK_DESCRIPTOR);
                data.readString();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }
}
