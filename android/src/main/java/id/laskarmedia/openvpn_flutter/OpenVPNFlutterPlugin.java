package id.laskarmedia.openvpn_flutter;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;

import androidx.annotation.NonNull;

import java.util.ArrayList;

import de.blinkt.openvpn.OnVPNStatusChangeListener;
import de.blinkt.openvpn.VPNHelper;
import de.blinkt.openvpn.core.OpenVPNService;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodChannel;

import android.util.Log;

public class OpenVPNFlutterPlugin implements FlutterPlugin, ActivityAware {

    private static final String TAG = "OpenVPNFlutterPlugin";

    private MethodChannel vpnControlMethod;
    private EventChannel vpnStageEvent;
    private EventChannel.EventSink vpnStageSink;

    private static final String EVENT_CHANNEL_VPN_STAGE = "id.laskarmedia.openvpn_flutter/vpnstage";
    private static final String METHOD_CHANNEL_VPN_CONTROL = "id.laskarmedia.openvpn_flutter/vpncontrol";

    private static String config = "", username = "", password = "", name = "";

    private static ArrayList<String> bypassPackages;
    @SuppressLint("StaticFieldLeak")
    private static VPNHelper vpnHelper;
    private Activity activity;

    Context mContext;

    public static void connectWhileGranted(boolean granted) {
        Log.d(TAG, "connectWhileGranted: granted=" + granted);
        if (granted) {
            Log.d(TAG, "Starting VPN...");
            vpnHelper.startVPN(config, username, password, name, bypassPackages);
        } else {
            Log.e(TAG, "Permission not granted");
        }
    }

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        Log.d(TAG, "onAttachedToEngine: Initializing plugin");
        vpnStageEvent = new EventChannel(binding.getBinaryMessenger(), EVENT_CHANNEL_VPN_STAGE);
        vpnControlMethod = new MethodChannel(binding.getBinaryMessenger(), METHOD_CHANNEL_VPN_CONTROL);

        vpnStageEvent.setStreamHandler(new EventChannel.StreamHandler() {
            @Override
            public void onListen(Object arguments, EventChannel.EventSink events) {
                Log.d(TAG, "onListen: VPN Stage Event listener added");
                vpnStageSink = events;
            }

            @Override
            public void onCancel(Object arguments) {
                Log.d(TAG, "onCancel: VPN Stage Event listener removed");
                if (vpnStageSink != null) vpnStageSink.endOfStream();
            }
        });

        vpnControlMethod.setMethodCallHandler((call, result) -> {
            Log.d(TAG, "Method call received: " + call.method);
            switch (call.method) {
                case "status":
                    handleStatus(result);
                    break;
                case "initialize":
                    initializeVPNHelper(result);
                    break;
                case "disconnect":
                    handleDisconnect(result);
                    break;
                case "connect":
                    handleConnect(call, result);
                    break;
                case "stage":
                    handleStage(result);
                    break;
                case "request_permission":
                    handleRequestPermission(result);
                    break;
                default:
                    Log.w(TAG, "Unknown method: " + call.method);
                    result.notImplemented();
            }
        });
        mContext = binding.getApplicationContext();
    }

    private void handleStatus(MethodChannel.Result result) {
        if (vpnHelper == null) {
            Log.e(TAG, "VPNEngine is not initialized");
            result.error("-1", "VPNEngine need to be initialized", "");
            return;
        }
        Log.d(TAG, "VPN status: " + vpnHelper.status.toString());
        result.success(vpnHelper.status.toString());
    }

    private void initializeVPNHelper(MethodChannel.Result result) {
        Log.d(TAG, "Initializing VPN Helper");
        vpnHelper = new VPNHelper(activity);
        vpnHelper.setOnVPNStatusChangeListener(new OnVPNStatusChangeListener() {
            @Override
            public void onVPNStatusChanged(String status) {
                Log.d(TAG, "VPN status changed: " + status);
                updateStage(status);
            }

            @Override
            public void onConnectionStatusChanged(String duration, String lastPacketReceive, String byteIn, String byteOut) {
                Log.d(TAG, "Connection status: duration=" + duration + ", byteIn=" + byteIn + ", byteOut=" + byteOut);
            }
        });
        result.success(updateVPNStages());
    }

    private void handleDisconnect(MethodChannel.Result result) {
        if (vpnHelper == null) {
            Log.e(TAG, "VPNEngine is not initialized");
            result.error("-1", "VPNEngine need to be initialized", "");
            return;
        }
        Log.d(TAG, "Disconnecting VPN...");
        vpnHelper.stopVPN();
        updateStage("disconnected");
    }

    private void handleConnect(MethodChannel.MethodCall call, MethodChannel.Result result) {
        Log.d(TAG, "Connecting to VPN...");
        if (vpnHelper == null) {
            Log.e(TAG, "VPNEngine is not initialized");
            result.error("-1", "VPNEngine need to be initialized", "");
            return;
        }

        config = call.argument("config");
        name = call.argument("name");
        username = call.argument("username");
        password = call.argument("password");
        bypassPackages = call.argument("bypass_packages");

        if (config == null) {
            Log.e(TAG, "OpenVPN Config is required");
            result.error("-2", "OpenVPN Config is required", "");
            return;
        }

        final Intent permission = VpnService.prepare(activity);
        if (permission != null) {
            Log.d(TAG, "Requesting VPN permission...");
            activity.startActivityForResult(permission, 24);
            return;
        }
        startForegroundVPNService();
        vpnHelper.startVPN(config, username, password, name, bypassPackages);
    }

    private void startForegroundVPNService() {
        Log.d(TAG, "Starting foreground VPN service");
        Notification notification = createVPNNotification();
        OpenVPNService.startForeground(1, notification, Notification.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
    }

    private void handleStage(MethodChannel.Result result) {
        if (vpnHelper == null) {
            Log.e(TAG, "VPNEngine is not initialized");
            result.error("-1", "VPNEngine need to be initialized", "");
            return;
        }
        result.success(updateVPNStages());
    }

    private void handleRequestPermission(MethodChannel.Result result) {
        final Intent request = VpnService.prepare(activity);
        if (request != null) {
            Log.d(TAG, "Requesting VPN permission...");
            activity.startActivityForResult(request, 24);
            result.success(false);
            return;
        }
        result.success(true);
    }

    private Notification createVPNNotification() {
        String channelId = "VPNChannel";
        NotificationManager manager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                channelId,
                "VPN Connection",
                NotificationManager.IMPORTANCE_LOW
            );
            manager.createNotificationChannel(channel);
        }

        return new Notification.Builder(mContext, channelId)
            .setContentTitle("VPN Active")
            .setContentText("Your VPN connection is active.")
            .setSmallIcon(android.R.drawable.ic_secure)
            .build();
    }

    public void updateStage(String stage) {
        Log.d(TAG, "Updating stage: " + stage);
        if (stage == null) stage = "idle";
        if (vpnStageSink != null) vpnStageSink.success(stage.toLowerCase());
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        Log.d(TAG, "onDetachedFromEngine: Cleaning up resources");
        vpnStageEvent.setStreamHandler(null);
        vpnControlMethod.setMethodCallHandler(null);
    }

    private String updateVPNStages() {
        if (OpenVPNService.getStatus() == null) {
            OpenVPNService.setDefaultStatus();
        }
        updateStage(OpenVPNService.getStatus());
        return OpenVPNService.getStatus();
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        Log.d(TAG, "onAttachedToActivity");
        activity = binding.getActivity();
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        Log.d(TAG, "onDetachedFromActivityForConfigChanges");
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        Log.d(TAG, "onReattachedToActivityForConfigChanges");
        activity = binding.getActivity();
    }

    @Override
    public void onDetachedFromActivity() {
        Log.d(TAG, "onDetachedFromActivity");
    }
}
