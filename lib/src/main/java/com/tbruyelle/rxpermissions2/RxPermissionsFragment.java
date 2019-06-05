package com.tbruyelle.rxpermissions2;

import android.Manifest;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.util.Log;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import io.reactivex.subjects.PublishSubject;

public class RxPermissionsFragment extends Fragment {

    private static final int PERMISSIONS_REQUEST_CODE = 42;

    // Contains all the current permission requests.
    // Once granted or denied, they are removed from it.
    private Map<String, PublishSubject<Permission>> mSubjects = new HashMap<>();
    private boolean mLogging;

    public RxPermissionsFragment() {
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRetainInstance(true);
    }

    @TargetApi(Build.VERSION_CODES.M)
    void requestPermissions(@NonNull String[] permissions) {
        requestPermissions(permissions, PERMISSIONS_REQUEST_CODE);
    }

    @TargetApi(Build.VERSION_CODES.M)
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != PERMISSIONS_REQUEST_CODE) return;

        boolean[] shouldShowRequestPermissionRationale = new boolean[permissions.length];

        for (int i = 0; i < permissions.length; i++) {
            shouldShowRequestPermissionRationale[i] = shouldShowRequestPermissionRationale(permissions[i]);
        }

        onRequestPermissionsResult(permissions, grantResults, shouldShowRequestPermissionRationale);
    }

    void onRequestPermissionsResult(String permissions[], int[] grantResults, boolean[] shouldShowRequestPermissionRationale) {
        for (int i = 0, size = permissions.length; i < size; i++) {
            log("onRequestPermissionsResult  " + permissions[i]);
            // Find the corresponding subject
            PublishSubject<Permission> subject = mSubjects.get(permissions[i]);
            if (subject == null) {
                // No subject found
                Log.e(RxPermissions.TAG, "RxPermissions.onRequestPermissionsResult invoked but didn't find the corresponding permission request.");
                return;
            }
            mSubjects.remove(permissions[i]);
            boolean granted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
            // 在此处判断权限是否验证通过
            if (granted) {
                granted = granted && specialHandle(permissions[i], getContext());
            }
            subject.onNext(new Permission(permissions[i], granted, shouldShowRequestPermissionRationale[i]));
            subject.onComplete();
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    boolean isGranted(String permission) {
        final FragmentActivity fragmentActivity = getActivity();
        if (fragmentActivity == null) {
            throw new IllegalStateException("This fragment must be attached to an activity.");
        }
        return fragmentActivity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    @TargetApi(Build.VERSION_CODES.M)
    boolean isRevoked(String permission) {
        final FragmentActivity fragmentActivity = getActivity();
        if (fragmentActivity == null) {
            throw new IllegalStateException("This fragment must be attached to an activity.");
        }
        return fragmentActivity.getPackageManager().isPermissionRevokedByPolicy(permission, getActivity().getPackageName());
    }

    public void setLogging(boolean logging) {
        mLogging = logging;
    }

    public PublishSubject<Permission> getSubjectByPermission(@NonNull String permission) {
        return mSubjects.get(permission);
    }

    public boolean containsByPermission(@NonNull String permission) {
        return mSubjects.containsKey(permission);
    }

    public void setSubjectForPermission(@NonNull String permission, @NonNull PublishSubject<Permission> subject) {
        mSubjects.put(permission, subject);
    }

    void log(String message) {
        if (mLogging) {
            Log.d(RxPermissions.TAG, message);
        }
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
