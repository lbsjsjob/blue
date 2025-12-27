package com.example.blue;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.view.KeyEvent;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.List;

public class BluetoothMonitorService extends Service {

    private static final String TAG = "BT_AUTO";
    private BluetoothReceiver mReceiver;
    private Handler mHandler;
    private PowerManager.WakeLock mWakeLock;
    private int mRetryCount = 0;
    private static final int MAX_RETRY = 3;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "服务创建");

        // 初始化Handler用于延迟操作
        mHandler = new Handler();

        // 获取WakeLock,确保锁屏状态下代码能执行
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BlueAuto::WakeLock");

        // 启动前台服务
        startForegroundService();

        // 注册蓝牙广播接收器
        mReceiver = new BluetoothReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED); // 监听SCO音频状态
        registerReceiver(mReceiver, filter);

        Log.d(TAG, "广播接收器已注册");
    }

    private class BluetoothReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

                if (device != null && isAudioDevice(device)) {
                    String deviceName = getDeviceName(device);
                    Log.d(TAG, "检测到音频设备连接: " + deviceName);

                    // 获取WakeLock,防止锁屏时休眠
                    if (!mWakeLock.isHeld()) {
                        mWakeLock.acquire(30000); // 最多持有30秒
                    }

                    // 重置重试计数
                    mRetryCount = 0;

                    // 先尝试拉起网易云
                    openMusicPlayer();

                    // 延迟执行播放逻辑,分阶段检查
                    schedulePlayMusic(context);
                }
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                Log.d(TAG, "蓝牙设备断开连接");
                // 释放WakeLock
                if (mWakeLock.isHeld()) {
                    mWakeLock.release();
                }
            }
        }
    }

    // 分阶段尝试播放
    private void schedulePlayMusic(Context context) {
        // 第一次尝试: 3秒后(快速连接场景)
        mHandler.postDelayed(() -> attemptPlay(context, 1), 3000);

        // 第二次尝试: 6秒后
        mHandler.postDelayed(() -> attemptPlay(context, 2), 6000);

        // 第三次尝试: 10秒后(稳妥方案)
        mHandler.postDelayed(() -> attemptPlay(context, 3), 10000);
    }

    private void attemptPlay(Context context, int attempt) {
        if (mRetryCount >= MAX_RETRY) {
            Log.w(TAG, "已达到最大重试次数,停止尝试");
            releaseLock();
            return;
        }

        Log.d(TAG, "第" + attempt + "次尝试播放");

        if (isBluetoothAudioOutputActive(context)) {
            Log.d(TAG, "蓝牙音频通道已激活,执行播放");
            boolean success = playMusicMultiMethod(context);

            if (success) {
                mRetryCount = MAX_RETRY; // 成功后停止重试
                // 3秒后释放锁
                mHandler.postDelayed(this::releaseLock, 3000);
            } else {
                mRetryCount++;
            }
        } else {
            Log.w(TAG, "音频通道未激活,等待下次尝试");
            mRetryCount++;
        }
    }

    private void releaseLock() {
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
            Log.d(TAG, "WakeLock已释放");
        }
    }

    private void openMusicPlayer() {
        try {
            // 方法1: 启动网易云服务
            Intent serviceIntent = new Intent();
            serviceIntent.setClassName("com.netease.cloudmusic",
                    "com.netease.cloudmusic.service.PlayService");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            Log.d(TAG, "已尝试启动网易云服务");
        } catch (Exception e) {
            Log.e(TAG, "启动网易云服务失败: " + e.getMessage());

            // 备选方案: 启动网易云主界面
            try {
                Intent launchIntent = getPackageManager()
                        .getLaunchIntentForPackage("com.netease.cloudmusic");
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(launchIntent);
                    Log.d(TAG, "已启动网易云界面");
                }
            } catch (Exception e2) {
                Log.e(TAG, "启动网易云界面也失败: " + e2.getMessage());
            }
        }
    }

    // 多种方法组合播放
    private boolean playMusicMultiMethod(Context context) {
        boolean success = false;

        // 方法1: MediaButton广播 (主要方法)
        success = sendMediaButtonEvent(context);

        // 方法2: MediaSession控制 (Android 5.0+)
        if (!success && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            success = controlViaMediaSession(context);
        }

        // 方法3: 再次发送播放按钮(增强稳定性)
        if (success) {
            mHandler.postDelayed(() -> sendMediaButtonEvent(context), 500);
        }

        return success;
    }

    // 发送媒体按钮事件
    private boolean sendMediaButtonEvent(Context context) {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

            // 创建广播Intent
            Intent mediaIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
            mediaIntent.setPackage("com.netease.cloudmusic");

            // 按下播放键
            KeyEvent downEvent = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY);
            mediaIntent.putExtra(Intent.EXTRA_KEY_EVENT, downEvent);
            context.sendOrderedBroadcast(mediaIntent, null);

            // 松开播放键
            KeyEvent upEvent = new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY);
            mediaIntent.putExtra(Intent.EXTRA_KEY_EVENT, upEvent);
            context.sendOrderedBroadcast(mediaIntent, null);

            Log.d(TAG, "已发送MediaButton播放事件");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "发送MediaButton失败: " + e.getMessage());
            return false;
        }
    }

    // 通过MediaSession控制播放
    private boolean controlViaMediaSession(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false;
        }

        try {
            MediaSessionManager sessionManager =
                    (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);

            if (sessionManager != null) {
                List<MediaController> controllers = sessionManager.getActiveSessions(null);

                for (MediaController controller : controllers) {
                    if (controller.getPackageName().equals("com.netease.cloudmusic")) {
                        controller.getTransportControls().play();
                        Log.d(TAG, "通过MediaSession发送播放指令");
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "MediaSession控制失败: " + e.getMessage());
        }
        return false;
    }

    private boolean isAudioDevice(BluetoothDevice device) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.BLUETOOTH_CONNECT)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "缺少BLUETOOTH_CONNECT权限");
                return false;
            }
        }

        if (device != null && device.getBluetoothClass() != null) {
            int deviceClass = device.getBluetoothClass().getDeviceClass();
            return (deviceClass == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET ||
                    deviceClass == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES ||
                    deviceClass == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER ||
                    deviceClass == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO);
        }
        return false;
    }

    private String getDeviceName(BluetoothDevice device) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.BLUETOOTH_CONNECT)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return "未知设备";
            }
        }
        return device.getName() != null ? device.getName() : "未知设备";
    }

    private boolean isBluetoothAudioOutputActive(Context context) {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context,
                    android.Manifest.permission.BLUETOOTH_CONNECT)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "缺少权限,无法确认蓝牙音频状态");
                return false;
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.media.AudioDeviceInfo[] devices =
                    audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            for (android.media.AudioDeviceInfo device : devices) {
                int type = device.getType();
                if (type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    Log.d(TAG, "检测到蓝牙音频设备已激活");
                    return true;
                }
            }
            return false;
        } else {
            return audioManager.isBluetoothA2dpOn();
        }
    }

    private void startForegroundService() {
        String channelId = "bt_music_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    "蓝牙自动播放",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }

        Intent notificationIntent = new Intent(this, BluetoothMonitorService.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, channelId)
                .setContentTitle("蓝牙自动播放")
                .setContentText("正在监听蓝牙连接...")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();

        startForeground(1, notification);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // 确保服务被杀后自动重启
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "服务销毁");

        if (mReceiver != null) {
            unregisterReceiver(mReceiver);
        }

        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
        }

        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}