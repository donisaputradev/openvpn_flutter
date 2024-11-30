package id.laskarmedia.openvpn_flutter;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

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

    private MethodChannel vpnControlMethod;
    private EventChannel vpnStageEvent;
    //    private EventChannel vpnStatusEvent;
    private EventChannel.EventSink vpnStageSink;
//    private EventChannel.EventSink vpnStatusSink;

    private static final String EVENT_CHANNEL_VPN_STAGE = "id.laskarmedia.openvpn_flutter/vpnstage";
    //    private static final String EVENT_CHANNEL_VPN_STATUS = "id.laskarmedia.openvpn_flutter/vpnstatus";
    private static final String METHOD_CHANNEL_VPN_CONTROL = "id.laskarmedia.openvpn_flutter/vpncontrol";

    private static String config = "", username = "", password = "", name = "";

    private static ArrayList<String> bypassPackages;
    @SuppressLint("StaticFieldLeak")
    private static VPNHelper vpnHelper;
    private Activity activity;

    Context mContext;


    public static void connectWhileGranted(boolean granted) {
        if (granted) {
            vpnHelper.startVPN(config, username, password, name, bypassPackages);
        }
    }

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        vpnStageEvent = new EventChannel(binding.getBinaryMessenger(), EVENT_CHANNEL_VPN_STAGE);
        vpnControlMethod = new MethodChannel(binding.getBinaryMessenger(), METHOD_CHANNEL_VPN_CONTROL);

        vpnStageEvent.setStreamHandler(new EventChannel.StreamHandler() {
            @Override
            public void onListen(Object arguments, EventChannel.EventSink events) {
                vpnStageSink = events;
            }

            @Override
            public void onCancel(Object arguments) {
                if (vpnStageSink != null) vpnStageSink.endOfStream();
            }
        });

        vpnControlMethod.setMethodCallHandler((call, result) -> {
            System.out.println("OpenVPN: Received Method Call - " + call.method);

            switch (call.method) {
                case "status":
                    System.out.println("OpenVPN: Checking VPN Status");
                    if (vpnHelper == null) {
                        result.error("-1", "VPNEngine need to be initialize", "");
                        System.out.println("OpenVPN: VPNEngine is not initialized");
                        return;
                    }
                    result.success(vpnHelper.status.toString());
                    System.out.println("OpenVPN: VPN Status - " + vpnHelper.status.toString());
                    break;
                case "initialize":
                    System.out.println("OpenVPN: Initializing VPNHelper");
                    vpnHelper = new VPNHelper(activity);
                    vpnHelper.setOnVPNStatusChangeListener(new OnVPNStatusChangeListener() {
                        @Override
                        public void onVPNStatusChanged(String status) {
                            System.out.println("OpenVPN: VPN Status Changed - " + status);
                            updateStage(status);
                        }

                        @Override
                        public void onConnectionStatusChanged(String duration, String lastPacketReceive, String byteIn, String byteOut) {
                            System.out.println("OpenVPN: Connection Stats - Duration=" + duration + 
                                ", Last Packet Received=" + lastPacketReceive + 
                                ", Bytes In=" + byteIn + ", Bytes Out=" + byteOut);
                        }
                    });
                    result.success(updateVPNStages());
                    System.out.println("OpenVPN: VPNHelper Initialized");
                    break;
                case "disconnect":
                    System.out.println("OpenVPN: Disconnecting VPN");
                    if (vpnHelper == null) {
                        result.error("-1", "VPNEngine needs to be initialized", "");
                        System.out.println("OpenVPN: VPNEngine is not initialized");
                        return;
                    }
                    vpnHelper.stopVPN();
                    updateStage("disconnected");
                    System.out.println("OpenVPN: VPN Disconnected");
                    break;
                case "connect":
                    System.out.println("OpenVPN: Starting Connection");
                    if (vpnHelper == null) {
                        result.error("-1", "VPNEngine need to be initialize", "");
                        System.out.println("OpenVPN: VPNEngine is not initialized");
                        return;
                    }

                    config = call.argument("config");
                    name = call.argument("name");
                    username = call.argument("username");
                    password = call.argument("password");
                    bypassPackages = call.argument("bypass_packages");

                    if (config == null) {
                        result.error("-2", "OpenVPN Config is required", "");
                        System.out.println("OpenVPN: Missing Config File");
                        return;
                    }

                    final Intent permission = VpnService.prepare(activity);
                    if (permission != null) {
                        activity.startActivityForResult(permission, 24);
                        System.out.println("OpenVPN: Requesting VPN Permission");
                        return;
                    }
                    vpnHelper.startVPN(config, username, password, name, bypassPackages);
                    System.out.println("OpenVPN: VPN Started with Config - " + config);
                    break;
                case "stage":
                    if (vpnHelper == null) {
                        result.error("-1", "VPNEngine need to be initialize", "");
                        return;
                    }
                    result.success(updateVPNStages());
                    break;
                case "request_permission":
                    final Intent request = VpnService.prepare(activity);
                    if (request != null) {
                        activity.startActivityForResult(request, 24);
                        result.success(false);
                        return;
                    }
                    result.success(true);
                    break;

                default:
                    System.out.println("OpenVPN: Unknown Method - " + call.method);
            }
        });
        mContext = binding.getApplicationContext();
    }

    public void updateStage(String stage) {
        if (stage == null) stage = "idle";
        System.out.println("OpenVPN: Stage Updated - " + stage);
        if (vpnStageSink != null) vpnStageSink.success(stage.toLowerCase());
    }


    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        vpnStageEvent.setStreamHandler(null);
        vpnControlMethod.setMethodCallHandler(null);
//        vpnStatusEvent.setStreamHandler(null);
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
        activity = binding.getActivity();
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {

    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        activity = binding.getActivity();
    }

    @Override
    public void onDetachedFromActivity() {

    }
}