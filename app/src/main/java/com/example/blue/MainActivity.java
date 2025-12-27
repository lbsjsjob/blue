package com.example.blue;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
            }, 100);
        }

        // 1. 检查并请求所有必要的权限
        checkAndRequestPermissions();
    }

    private void checkAndRequestPermissions() {
        // 创建一个列表，存放需要申请但尚未获得授权的权限
        List<String> permissionsNeeded = new ArrayList<>();

        // 蓝牙连接权限 (针对 Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }

        // 通知权限 (针对 Android 13+)
        // TIRAMISU 是 Android 13 的版本代号
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        // 如果列表不为空，说明有权限需要申请
        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permissionsNeeded.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        } else {
            // 所有权限都已经授权，直接启动服务
            startMonitorService();
        }
    }

    // 2. 启动服务
    private void startMonitorService() {
        Intent serviceIntent = new Intent(this, BluetoothMonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        Toast.makeText(this, "蓝牙监听服务已启动", Toast.LENGTH_SHORT).show();
    }

    // 3. 权限回调处理
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            if (grantResults.length > 0) {
                for (int result : grantResults) {
                    if (result != PackageManager.PERMISSION_GRANTED) {
                        allGranted = false;
                        break;
                    }
                }
            } else {
                allGranted = false;
            }

            if (allGranted) {
                // 用户点击了“允许”，启动服务
                startMonitorService();
            } else {
                // 用户点击了“拒绝”
                Toast.makeText(this, "需要蓝牙和通知权限才能正常工作", Toast.LENGTH_LONG).show();
            }
        }
    }
}