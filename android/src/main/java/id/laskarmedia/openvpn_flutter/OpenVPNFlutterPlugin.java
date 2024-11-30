package id.laskarmedia.openvpn_flutter;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

import android.util.Log; // Tambahkan ini untuk logging

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

/**
 * OpenvpnFlutterPlugin
 */
public class OpenVPNFlutterPlugin implements FlutterPlugin, ActivityAware {

    private static final String TAG = "OpenVPNFlutterPlugin"; // Tambahkan TAG untuk log

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
        Log.d(TAG, "connectWhileGranted called with granted: " + granted);
        if (granted) {
            Log.d(TAG, "Starting VPN with config: " + config);
            vpnHelper.startVPN(config, username, password, name, bypassPackages);
        }
    }

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        Log.d(TAG, "onAttachedToEngine called");
        vpnStageEvent = new EventChannel(binding.getBinaryMessenger(), EVENT_CHANNEL_VPN_STAGE);
        vpnControlMethod = new MethodChannel(binding.getBinaryMessenger(), METHOD_CHANNEL_VPN_CONTROL);

        vpnStageEvent.setStreamHandler(new EventChannel.StreamHandler() {
            @Override
            public void onListen(Object arguments, EventChannel.EventSink events) {
                Log.d(TAG, "EventChannel vpnStageEvent: onListen called");
                vpnStageSink = events;
            }

            @Override
            public void onCancel(Object arguments) {
                Log.d(TAG, "EventChannel vpnStageEvent: onCancel called");
                if (vpnStageSink != null) vpnStageSink.endOfStream();
            }
        });

        vpnControlMethod.setMethodCallHandler((call, result) -> {
            Log.d(TAG, "Method call received: " + call.method);
            switch (call.method) {
                case "status":
                    if (vpnHelper == null) {
                        Log.e(TAG, "VPNEngine not initialized");
                        result.error("-1", "VPNEngine need to be initialize", "");
                        return;
                    }
                    Log.d(TAG, "VPN status: " + vpnHelper.status.toString());
                    result.success(vpnHelper.status.toString());
                    break;
                case "initialize":
                    Log.d(TAG, "Initializing VPNHelper");
                    vpnHelper = new VPNHelper(activity);
                    vpnHelper.setOnVPNStatusChangeListener(new OnVPNStatusChangeListener() {
                        @Override
                        public void onVPNStatusChanged(String status) {
                            Log.d(TAG, "VPN status changed to: " + status);
                            updateStage(status);
                        }

                        @Override
                        public void onConnectionStatusChanged(String duration, String lastPacketReceive, String byteIn, String byteOut) {
                            Log.d(TAG, "Connection Status - Duration: " + duration + ", Byte In: " + byteIn + ", Byte Out: " + byteOut);
                        }
                    });
                    result.success(updateVPNStages());
                    break;
                case "disconnect":
                    if (vpnHelper == null) {
                        Log.e(TAG, "VPNEngine not initialized");
                        result.error("-1", "VPNEngine need to be initialize", "");
                        return;
                    }
                    Log.d(TAG, "Disconnecting VPN");
                    vpnHelper.stopVPN();
                    updateStage("disconnected");
                    break;
                case "connect":
                    if (vpnHelper == null) {
                        Log.e(TAG, "VPNEngine not initialized");
                        result.error("-1", "VPNEngine need to be initialize", "");
                        return;
                    }

                    config = call.argument("config");
                    name = call.argument("name");
                    username = call.argument("username");
                    password = call.argument("password");
                    bypassPackages = call.argument("bypass_packages");

                    if (config == null) {
                        Log.e(TAG, "Config is null. OpenVPN Config is required");
                        result.error("-2", "OpenVPN Config is required", "");
                        return;
                    }

                    Log.d(TAG, "Starting VPN connection with name: " + name);
                    final Intent permission = VpnService.prepare(activity);
                    if (permission != null) {
                        Log.d(TAG, "Requesting VPN permission");
                        activity.startActivityForResult(permission, 24);
                        return;
                    }
                    vpnHelper.startVPN(config, username, password, name, bypassPackages);
                    break;
                case "stage":
                    if (vpnHelper == null) {
                        Log.e(TAG, "VPNEngine not initialized");
                        result.error("-1", "VPNEngine need to be initialize", "");
                        return;
                    }
                    String stage = updateVPNStages();
                    Log.d(TAG, "VPN stage: " + stage);
                    result.success(stage);
                    break;
                case "request_permission":
                    final Intent request = VpnService.prepare(activity);
                    if (request != null) {
                        Log.d(TAG, "Requesting VPN permission");
                        activity.startActivityForResult(request, 24);
                        result.success(false);
                        return;
                    }
                    Log.d(TAG, "VPN permission granted");
                    result.success(true);
                    break;

                default:
                    Log.w(TAG, "Unknown method: " + call.method);
            }
        });
        mContext = binding.getApplicationContext();
    }

    public void updateStage(String stage) {
        if (stage == null) stage = "idle";
        Log.d(TAG, "Updating stage to: " + stage);
        if (vpnStageSink != null) vpnStageSink.success(stage.toLowerCase());
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        Log.d(TAG, "onDetachedFromEngine called");
        vpnStageEvent.setStreamHandler(null);
        vpnControlMethod.setMethodCallHandler(null);
    }

    private String updateVPNStages() {
        Log.d(TAG, "Updating VPN stages");
        if (OpenVPNService.getStatus() == null) {
            Log.d(TAG, "Setting default VPN status");
            OpenVPNService.setDefaultStatus();
        }
        String status = OpenVPNService.getStatus();
        updateStage(status);
        return status;
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        Log.d(TAG, "onAttachedToActivity called");
        activity = binding.getActivity();
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        Log.d(TAG, "onDetachedFromActivityForConfigChanges called");
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        Log.d(TAG, "onReattachedToActivityForConfigChanges called");
        activity = binding.getActivity();
    }

    @Override
    public void onDetachedFromActivity() {
        Log.d(TAG, "onDetachedFromActivity called");
    }
}
