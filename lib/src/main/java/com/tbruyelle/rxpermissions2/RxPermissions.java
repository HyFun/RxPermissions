/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tbruyelle.rxpermissions2;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.hardware.Camera;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.annotation.VisibleForTesting;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.text.TextUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.ObservableSource;
import io.reactivex.ObservableTransformer;
import io.reactivex.functions.Function;
import io.reactivex.subjects.PublishSubject;

public class RxPermissions {

    static final String TAG = RxPermissions.class.getSimpleName();
    static final Object TRIGGER = new Object();

    @VisibleForTesting
    Lazy<RxPermissionsFragment> mRxPermissionsFragment;

    public RxPermissions(@NonNull final FragmentActivity activity) {
        mRxPermissionsFragment = getLazySingleton(activity.getSupportFragmentManager());
    }

    public RxPermissions(@NonNull final Fragment fragment) {
        mRxPermissionsFragment = getLazySingleton(fragment.getChildFragmentManager());
    }

    @NonNull
    private Lazy<RxPermissionsFragment> getLazySingleton(@NonNull final FragmentManager fragmentManager) {
        return new Lazy<RxPermissionsFragment>() {

            private RxPermissionsFragment rxPermissionsFragment;

            @Override
            public synchronized RxPermissionsFragment get() {
                if (rxPermissionsFragment == null) {
                    rxPermissionsFragment = getRxPermissionsFragment(fragmentManager);
                }
                return rxPermissionsFragment;
            }

        };
    }

    private RxPermissionsFragment getRxPermissionsFragment(@NonNull final FragmentManager fragmentManager) {
        RxPermissionsFragment rxPermissionsFragment = findRxPermissionsFragment(fragmentManager);
        boolean isNewInstance = rxPermissionsFragment == null;
        if (isNewInstance) {
            rxPermissionsFragment = new RxPermissionsFragment();
            fragmentManager
                    .beginTransaction()
                    .add(rxPermissionsFragment, TAG)
                    .commitNow();
        }
        return rxPermissionsFragment;
    }

    private RxPermissionsFragment findRxPermissionsFragment(@NonNull final FragmentManager fragmentManager) {
        return (RxPermissionsFragment) fragmentManager.findFragmentByTag(TAG);
    }

    public void setLogging(boolean logging) {
        mRxPermissionsFragment.get().setLogging(logging);
    }

    /**
     * Map emitted items from the source observable into {@code true} if permissions in parameters
     * are granted, or {@code false} if not.
     * <p>
     * If one or several permissions have never been requested, invoke the related framework method
     * to ask the user if he allows the permissions.
     */
    @SuppressWarnings("WeakerAccess")
    public <T> ObservableTransformer<T, Boolean> ensure(final String... permissions) {
        return new ObservableTransformer<T, Boolean>() {
            @Override
            public ObservableSource<Boolean> apply(Observable<T> o) {
                return request(o, permissions)
                        // Transform Observable<Permission> to Observable<Boolean>
                        .buffer(permissions.length)
                        .flatMap(new Function<List<Permission>, ObservableSource<Boolean>>() {
                            @Override
                            public ObservableSource<Boolean> apply(List<Permission> permissions) {
                                if (permissions.isEmpty()) {
                                    // Occurs during orientation change, when the subject receives onComplete.
                                    // In that case we don't want to propagate that empty list to the
                                    // subscriber, only the onComplete.
                                    return Observable.empty();
                                }
                                // Return true if all permissions are granted.
                                for (Permission p : permissions) {
                                    if (!p.granted) {
                                        return Observable.just(false);
                                    }
                                }
                                return Observable.just(true);
                            }
                        });
            }
        };
    }

    /**
     * Map emitted items from the source observable into {@link Permission} objects for each
     * permission in parameters.
     * <p>
     * If one or several permissions have never been requested, invoke the related framework method
     * to ask the user if he allows the permissions.
     */
    @SuppressWarnings("WeakerAccess")
    public <T> ObservableTransformer<T, Permission> ensureEach(final String... permissions) {
        return new ObservableTransformer<T, Permission>() {
            @Override
            public ObservableSource<Permission> apply(Observable<T> o) {
                return request(o, permissions);
            }
        };
    }

    /**
     * Map emitted items from the source observable into one combined {@link Permission} object. Only if all permissions are granted,
     * permission also will be granted. If any permission has {@code shouldShowRationale} checked, than result also has it checked.
     * <p>
     * If one or several permissions have never been requested, invoke the related framework method
     * to ask the user if he allows the permissions.
     */
    public <T> ObservableTransformer<T, Permission> ensureEachCombined(final String... permissions) {
        return new ObservableTransformer<T, Permission>() {
            @Override
            public ObservableSource<Permission> apply(Observable<T> o) {
                return request(o, permissions)
                        .buffer(permissions.length)
                        .flatMap(new Function<List<Permission>, ObservableSource<Permission>>() {
                            @Override
                            public ObservableSource<Permission> apply(List<Permission> permissions) {
                                if (permissions.isEmpty()) {
                                    return Observable.empty();
                                }
                                return Observable.just(new Permission(permissions));
                            }
                        });
            }
        };
    }

    /**
     * Request permissions immediately, <b>must be invoked during initialization phase
     * of your application</b>.
     */
    @SuppressWarnings({"WeakerAccess", "unused"})
    public Observable<Boolean> request(final String... permissions) {
        return Observable.just(TRIGGER).compose(ensure(permissions));
    }

    /**
     * Request permissions immediately, <b>must be invoked during initialization phase
     * of your application</b>.
     */
    @SuppressWarnings({"WeakerAccess", "unused"})
    public Observable<Permission> requestEach(final String... permissions) {
        return Observable.just(TRIGGER).compose(ensureEach(permissions));
    }

    /**
     * Request permissions immediately, <b>must be invoked during initialization phase
     * of your application</b>.
     */
    public Observable<Permission> requestEachCombined(final String... permissions) {
        return Observable.just(TRIGGER).compose(ensureEachCombined(permissions));
    }

    private Observable<Permission> request(final Observable<?> trigger, final String... permissions) {
        if (permissions == null || permissions.length == 0) {
            throw new IllegalArgumentException("RxPermissions.request/requestEach requires at least one input permission");
        }
        return oneOf(trigger, pending(permissions))
                .flatMap(new Function<Object, Observable<Permission>>() {
                    @Override
                    public Observable<Permission> apply(Object o) {
                        return requestImplementation(permissions);
                    }
                })
                .flatMap(new Function<Permission, ObservableSource<Permission>>() {
                    @Override
                    public ObservableSource<Permission> apply(Permission permission) throws Exception {
                        Permission newP = null;
                        if (permission.granted) {
                            newP = new Permission(permission.name, specialHandle(permission.name, mRxPermissionsFragment.get().getContext()), permission.shouldShowRequestPermissionRationale);
                        } else {
                            newP = permission;
                        }
                        final Permission finalNewP = newP;
                        return Observable.create(new ObservableOnSubscribe<Permission>() {
                            @Override
                            public void subscribe(ObservableEmitter<Permission> emitter) throws Exception {
                                emitter.onNext(finalNewP);
                                emitter.onComplete();
                            }
                        });
                    }
                });
    }

    private Observable<?> pending(final String... permissions) {
        for (String p : permissions) {
            if (!mRxPermissionsFragment.get().containsByPermission(p)) {
                return Observable.empty();
            }
        }
        return Observable.just(TRIGGER);
    }

    private Observable<?> oneOf(Observable<?> trigger, Observable<?> pending) {
        if (trigger == null) {
            return Observable.just(TRIGGER);
        }
        return Observable.merge(trigger, pending);
    }

    @TargetApi(Build.VERSION_CODES.M)
    private Observable<Permission> requestImplementation(final String... permissions) {
        List<Observable<Permission>> list = new ArrayList<>(permissions.length);
        List<String> unrequestedPermissions = new ArrayList<>();

        // In case of multiple permissions, we create an Observable for each of them.
        // At the end, the observables are combined to have a unique response.
        for (String permission : permissions) {
            mRxPermissionsFragment.get().log("Requesting permission " + permission);
            if (isGranted(permission)) {
                // Already granted, or not Android M
                // Return a granted Permission object.
                list.add(Observable.just(new Permission(permission, true, false)));
                continue;
            }

            if (isRevoked(permission)) {
                // Revoked by a policy, return a denied Permission object.
                list.add(Observable.just(new Permission(permission, false, false)));
                continue;
            }

            PublishSubject<Permission> subject = mRxPermissionsFragment.get().getSubjectByPermission(permission);
            // Create a new subject if not exists
            if (subject == null) {
                unrequestedPermissions.add(permission);
                subject = PublishSubject.create();
                mRxPermissionsFragment.get().setSubjectForPermission(permission, subject);
            }

            list.add(subject);
        }

        if (!unrequestedPermissions.isEmpty()) {
            String[] unrequestedPermissionsArray = unrequestedPermissions.toArray(new String[unrequestedPermissions.size()]);
            requestPermissionsFromFragment(unrequestedPermissionsArray);
        }
        return Observable.concat(Observable.fromIterable(list));
    }

    /**
     * Invokes Activity.shouldShowRequestPermissionRationale and wraps
     * the returned value in an observable.
     * <p>
     * In case of multiple permissions, only emits true if
     * Activity.shouldShowRequestPermissionRationale returned true for
     * all revoked permissions.
     * <p>
     * You shouldn't call this method if all permissions have been granted.
     * <p>
     * For SDK &lt; 23, the observable will always emit false.
     */
    @SuppressWarnings("WeakerAccess")
    public Observable<Boolean> shouldShowRequestPermissionRationale(final Activity activity, final String... permissions) {
        if (!isMarshmallow()) {
            return Observable.just(false);
        }
        return Observable.just(shouldShowRequestPermissionRationaleImplementation(activity, permissions));
    }

    @TargetApi(Build.VERSION_CODES.M)
    private boolean shouldShowRequestPermissionRationaleImplementation(final Activity activity, final String... permissions) {
        for (String p : permissions) {
            if (!isGranted(p) && !activity.shouldShowRequestPermissionRationale(p)) {
                return false;
            }
        }
        return true;
    }

    @TargetApi(Build.VERSION_CODES.M)
    void requestPermissionsFromFragment(String[] permissions) {
        mRxPermissionsFragment.get().log("requestPermissionsFromFragment " + TextUtils.join(", ", permissions));
        mRxPermissionsFragment.get().requestPermissions(permissions);
    }

    /**
     * Returns true if the permission is already granted.
     * <p>
     * Always true if SDK &lt; 23.
     */
    @SuppressWarnings("WeakerAccess")
    public boolean isGranted(String permission) {
        return !isMarshmallow() || mRxPermissionsFragment.get().isGranted(permission);
    }

    /**
     * Returns true if the permission has been revoked by a policy.
     * <p>
     * Always false if SDK &lt; 23.
     */
    @SuppressWarnings("WeakerAccess")
    public boolean isRevoked(String permission) {
        return isMarshmallow() && mRxPermissionsFragment.get().isRevoked(permission);
    }

    boolean isMarshmallow() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    }

    void onRequestPermissionsResult(String permissions[], int[] grantResults) {
        mRxPermissionsFragment.get().onRequestPermissionsResult(permissions, grantResults, new boolean[permissions.length]);
    }

    @FunctionalInterface
    public interface Lazy<V> {
        V get();
    }


    /**
     * 特殊设备权限的处理
     *
     * @param permission
     * @param context
     * @return
     */
    boolean specialHandle(String permission, Context context) {
        if (!RxPermissionUtil.isSpecialDevice()) {
            return true;
        }
        if (permission.equals(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                || permission.equals(Manifest.permission.READ_EXTERNAL_STORAGE)) {
            return RxPermissionUtil.isStorage();
        } else if (permission.equals(Manifest.permission.ACCESS_COARSE_LOCATION)
                || permission.equals(Manifest.permission.ACCESS_FINE_LOCATION)) {
            return RxPermissionUtil.isLocation(context);
        } else if (permission.equals(Manifest.permission.CAMERA)) {
            return RxPermissionUtil.isCamera();
        } else if (permission.equals(Manifest.permission.RECORD_AUDIO)) {
            return RxPermissionUtil.isRecord();
        }
        return true;
    }

    private static class RxPermissionUtil {
        // 存储
        private static final boolean isStorage() {
            String filePath = Environment.getExternalStorageDirectory().getAbsolutePath() + File.separator + UUID.randomUUID() + ".txt";
            File file = new File(filePath);
            try {
                if (file.createNewFile()) {
                    file.delete();
                }
                return true;
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        }

        // 定位权限
        private static final boolean isLocation(Context context) {
            LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            // 构建位置查询条件
            Criteria criteria = new Criteria();
            // 设置定位精确度 Criteria.ACCURACY_COARSE比较粗略，Criteria.ACCURACY_FINE则比较精细
            //Criteria.ACCURACY_FINE,当使用该值时，在建筑物当中，可能定位不了,建议在对定位要求并不是很高的时候用Criteria.ACCURACY_COARSE，避免定位失败
            // 查询精度：高
            criteria.setAccuracy(Criteria.ACCURACY_FINE);
            // 设置是否要求速度
            criteria.setSpeedRequired(false);
            // 是否查询海拨：否
            criteria.setAltitudeRequired(false);
            // 是否查询方位角 : 否
            criteria.setBearingRequired(false);
            // 是否允许付费：是
            criteria.setCostAllowed(false);
            // 电量要求：低
            criteria.setPowerRequirement(Criteria.POWER_LOW);
            // 返回最合适的符合条件的provider，第2个参数为true说明 , 如果只有一个provider是有效的,则返回当前provider
            String bestProvider = locationManager.getBestProvider(criteria, true);
            Location location = locationManager.getLastKnownLocation(bestProvider);
            if (location == null) {
                location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
            if (location == null) {
                return false;
            }
            return true;
        }


        // 摄像头权限
        private static final boolean isCamera() {
            try {
                //通过尝试打开相机的方式判断有无拍照权限（在6.0以下使用拥有root权限的管理软件可以管理权限）
                boolean isCanUse = true;
                Camera mCamera = null;
                try {
                    mCamera = Camera.open();
                    Camera.Parameters mParameters = mCamera.getParameters();
                    mCamera.setParameters(mParameters);
                } catch (Exception e) {
                    isCanUse = false;
                } finally {
                    if (mCamera != null) {
                        try {
                            mCamera.release();
                        } catch (Exception e) {
                            e.printStackTrace();
                            isCanUse = false;
                        } finally {
                            return isCanUse;
                        }
                    } else {
                        return false;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        }

        // 录音权限
        private static final boolean isRecord() {
            try {
                // 音频获取源
                int audioSource = MediaRecorder.AudioSource.MIC;
                // 设置音频采样率，44100是目前的标准，但是某些设备仍然支持22050，16000，11025
                int sampleRateInHz = 44100;
                // 设置音频的录制的声道CHANNEL_IN_STEREO为双声道，CHANNEL_CONFIGURATION_MONO为单声道
                int channelConfig = AudioFormat.CHANNEL_IN_STEREO;
                // 音频数据格式:PCM 16位每个样本。保证设备支持。PCM 8位每个样本。不一定能得到设备支持。
                int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
                // 缓冲区字节大小
                int bufferSizeInBytes;
                boolean flag;
                bufferSizeInBytes = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat);
                AudioRecord audioRecord = new AudioRecord(audioSource, sampleRateInHz, channelConfig, audioFormat, bufferSizeInBytes);
                //开始录制音频
                try {
                    // 防止某些手机崩溃，例如联想
                    audioRecord.startRecording();
                } catch (IllegalStateException e) {
                    e.printStackTrace();
                } finally {
                    /**
                     * 根据开始录音判断是否有录音权限
                     */
                    if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                        flag = false;
                    else
                        flag = true;
                    audioRecord.stop();
                    audioRecord.release();
                    return flag;
                }
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        }
        // —————————————————————私有方法———————————————————

        private static final boolean isSpecialDevice() {
            return Build.BRAND.toLowerCase().equals("smartisan")
                    || Build.BRAND.toLowerCase().equals("xiaomi") || Build.BRAND.toLowerCase().equals("oppo") || Build.BRAND.toLowerCase().equals("vivo")
                    || Build.BRAND.toLowerCase().equals("lenovo") || Build.BRAND.toLowerCase().equals("meizu");
        }

    }


}
