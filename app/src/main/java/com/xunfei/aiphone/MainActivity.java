package com.xunfei.aiphone;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Vibrator;
import android.app.Service;
import android.util.Log;
import android.webkit.WebSettings;
import android.widget.Toast;

import com.github.lzyzsd.jsbridge.BridgeHandler;
import com.github.lzyzsd.jsbridge.BridgeWebView;
import com.github.lzyzsd.jsbridge.CallBackFunction;
import com.google.gson.Gson;
import com.iflytek.cloud.ErrorCode;
import com.iflytek.cloud.InitListener;
import com.iflytek.cloud.RecognizerListener;
import com.iflytek.cloud.RecognizerResult;
import com.iflytek.cloud.SpeechConstant;
import com.iflytek.cloud.SpeechError;
import com.iflytek.cloud.SpeechRecognizer;
import com.iflytek.cloud.SpeechUtility;
import com.xunfei.aiphone.dto.MapDTO;
import com.xunfei.aiphone.util.JsonParser;
import com.xunfei.aiphone.R;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;

public class MainActivity extends AppCompatActivity implements NetworkConnectChangedReceiver.ConnectChangedListener {
    private static String TAG = MainActivity.class.getSimpleName();
    private Vibrator mvibrator ;
    private Toast mToast;
    private NetworkConnectChangedReceiver connectChangedReceiver;

    // JS Bridge
    private BridgeWebView webView;

    // 语音听写对象
    private SpeechRecognizer mIat;

    // 语音引擎类型
    private String mEngineType = SpeechConstant.TYPE_CLOUD;

    private String language = "zh_cn";
    private String resultType = "json";
    private StringBuilder buffer = new StringBuilder();

    // 用HashMap存储听写结果
    private HashMap<String, String> mIatResults = new LinkedHashMap<String, String>();

    // 初始化监听器
    private InitListener mInitListener = new InitListener() {
        @Override
        public void onInit(int code) {
            Log.d(TAG, "SpeechRecognizer init() code = " + code);
            if (code != ErrorCode.SUCCESS) {
                toast("初始化失败，错误码：" + code); // https://www.xfyun.cn/document/error-code
            }
        }
    };

    // 听写监听器
    private RecognizerListener mRecognizerListener = new RecognizerListener() {

        @Override
        public void onBeginOfSpeech() {
            // 此回调表示：sdk内部录音机已经准备好了，用户可以开始语音输入
//            toast("开始说话");
        }

        @Override
        public void onError(SpeechError error) {
            // Tips：
            // 错误码：10118(您没有说话)，可能是录音机权限被禁，需要提示用户打开应用的录音权限。
            toast(error.getPlainDescription(true));
        }

        @Override
        public void onEndOfSpeech() {
            // 此回调表示：检测到了语音的尾端点，已经进入识别过程，不再接受语音输入
            toast("结束说话");
            launchHandleVoiceEnd(MapDTO.to("return_code", ErrorCode.SUCCESS, "data", "结束说话"));
        }

        @Override
        public void onResult(RecognizerResult results, boolean isLast) {
            Log.d(TAG, results.getResultString());
            String text = JsonParser.parseIatResult(results.getResultString());

            String sn = null;
            String pgs = null;
            String rg = null;
            String origin = "" ;
            // 读取json结果中的sn字段
            try {
                JSONObject resultJson = new JSONObject(results.getResultString());
                sn = resultJson.optString("sn");
                pgs = resultJson.optString("pgs");
                rg = resultJson.optString("rg");
            } catch (JSONException e) {
                e.printStackTrace();
            }

            StringBuffer originBuffer = new StringBuffer();
            for (String key : mIatResults.keySet()) {
                originBuffer.append(mIatResults.get(key));
            }
            origin = originBuffer.toString() ;
            //如果pgs是rpl就在已有的结果中删除掉要覆盖的sn部分
            if (pgs.equals("rpl")) {
                String[] strings = rg.replace("[", "").replace("]", "").split(",");
                int begin = Integer.parseInt(strings[0]);
                int end = Integer.parseInt(strings[1]);
                for (int i = begin; i <= end; i++) {
                    mIatResults.remove(i+"");
                }
            }

            mIatResults.put(sn, text);
            StringBuffer resultBuffer = new StringBuffer();
            for (String key : mIatResults.keySet()) {
                resultBuffer.append(mIatResults.get(key));
            }
            launchHandleVoiceResult(MapDTO.to("return_code", ErrorCode.SUCCESS, "data", resultBuffer.toString(), "pgs", pgs , "origin",origin, "is_last", isLast));
//            launchHandleVoiceResult(MapDTO.to("return_code", ErrorCode.SUCCESS, "data", JsonParser.parseIatResult(results.getResultString()), "is_last", isLast));
        }

        @Override
        public void onVolumeChanged(int volume, byte[] data) {
//            toast("当前正在说话，音量大小：" + volume);
//            Log.d(TAG, "返回音频数据：" + data.length);
        }

        @Override
        public void onEvent(int eventType, int arg1, int arg2, Bundle obj) {
            // 以下代码用于获取与云端的会话id，当业务出错时将会话id提供给技术支持人员，可用于查询会话日志，定位出错原因
            // 若使用本地能力，会话id为null
            //	if (SpeechEvent.EVENT_SESSION_ID == eventType) {
            //		String sid = obj.getString(SpeechEvent.KEY_EVENT_SESSION_ID);
            //		Log.d(TAG, "session id =" + sid);
            //	}
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SpeechUtility.createUtility(MainActivity.this, "appid=" + getString(R.string.app_id));

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Log.d(TAG, "create log test" );
        requestPermissions();

        mvibrator = (Vibrator) getSystemService(Service.VIBRATOR_SERVICE);
        mToast = Toast.makeText(this, "", Toast.LENGTH_SHORT);
        if (connectChangedReceiver == null) {
            connectChangedReceiver = new NetworkConnectChangedReceiver();
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(connectChangedReceiver, filter);
        connectChangedReceiver.setConnectChangeListener(this);

        // 初始化识别无UI识别对象
        // 使用SpeechRecognizer对象，可根据回调消息自定义界面；
        mIat = SpeechRecognizer.createRecognizer(this, mInitListener);

        initWebView();
    }

    @Override
    public void wifiNetwork(boolean flag) {
        Log.e("linksu", "wifiNetwork: " + flag);
        if( flag && webView != null ) {
            webView.reload();
            webView.clearCache(true);
        }
    }
    @Override
    public void notNetWork() {
        Log.e("notNetWork", "notNetWork: ");
    }

    @Override
    public void dataNetwork(boolean flag) {
        Log.e("linksu", "onReceive(dataNetwork:273) 切换到数据流量");
    }


    private void initWebView() {
        webView = findViewById(R.id.web_view);
        WebSettings webSettings = webView.getSettings();
        webSettings.setDomStorageEnabled(true);  // 开启 DOM storage 功能
        webSettings.setAppCacheMaxSize(1024*1024*8);
        String appCachePath = this.getApplicationContext().getCacheDir().getAbsolutePath();
        webSettings.setAppCachePath(appCachePath);
        webSettings.setAllowFileAccess(true);    // 可以读取文件缓存
        webSettings.setAppCacheEnabled(true);    //开启H5(APPCache)缓存功能
//        webSettings.setCacheMode(WebSettings.LOAD_NO_CACHE); // 方便测试，关闭了缓存。
//        webView.loadUrl("http://192.168.1.105:8080/index.html?_time=" + new Date().getTime());
//        webView.loadUrl("http://ai.7dtime.com/h5/index.html?_time=" + new Date().getTime());
        webView.loadUrl("http://ai.7dtime.com/h5/index.html");
//
        // 处理吐司消息
        webView.registerHandler("handleToast", new BridgeHandler() {
            @Override
            public void handler(String data, CallBackFunction function) {
                toast(data);
            }
        });
        webView.registerHandler("handleVibrate", new BridgeHandler() {
            @Override
            public void handler(String data, CallBackFunction function) {
                vibrate( Integer.parseInt(data));
            }
        });
        /*webView.registerHandler("sendEvent", new BridgeHandler() {
            @Override
            public void handler(String datastr, CallBackFunction function) {
                JSONObject data = JSONObject.fromObject(datastr);
                String eventName  = data.getString('eventName');
                if(eventName == 'toast') toast(data.getString('msg'));
                else if(eventName == 'vibrator') vibrate( data.getInt('time') );
            }
        });*/

        // 处理语音按下
        webView.registerHandler("handleVoiceDown", new BridgeHandler() {
            @Override
            public void handler(String data, CallBackFunction function) {
                buffer.setLength(0);
                mIatResults.clear();
//                vibrate(1000);
                initMIatParam();
                int returnCode = mIat.startListening(mRecognizerListener);
//                function.onCallBack("sssssssss");
//                function.onCallBack(new Gson().toJson(MapDTO.to("return_code", returnCode)));
            }
        });

        // 处理语音抬起
        webView.registerHandler("handleVoiceUp", new BridgeHandler() {
            @Override
            public void handler(String data, CallBackFunction function) {
                mIat.stopListening();
                function.onCallBack(new Gson().toJson(MapDTO.to("return_code", ErrorCode.SUCCESS)));
            }
        });
    }

    private void launchHandleVoiceResult(Object data) {
        webView.callHandler("handleVoiceResult", new Gson().toJson(data), new CallBackFunction() {
            @Override
            public void onCallBack(String data) {
                Log.i(TAG, "callHandler = handleVoiceResult,  data from web = " + data);
            }
        });
    }
    private void launchHandleVoiceEnd(Object data) {
        webView.callHandler("handleVoiceEnd" , new Gson().toJson(data), new CallBackFunction() {
            @Override
            public void onCallBack(String data) {
            }
        });
    }

    private void initMIatParam() {
        // 清空参数
        mIat.setParameter(SpeechConstant.PARAMS, null);
        // 设置听写引擎
        mIat.setParameter(SpeechConstant.ENGINE_TYPE, mEngineType);
        // 设置返回结果格式
        mIat.setParameter(SpeechConstant.RESULT_TYPE, resultType);

        if (language.equals("zh_cn")) {
            String lag = "mandarin"; // mSharedPreferences.getString("iat_language_preference", "mandarin");
            Log.e(TAG, "language:" + language);
            // 设置语言
            mIat.setParameter(SpeechConstant.LANGUAGE, "zh_cn");
            // 设置语言区域
            mIat.setParameter(SpeechConstant.ACCENT, lag);
        } else {
            mIat.setParameter(SpeechConstant.LANGUAGE, language);
        }
        Log.e(TAG, "last language:" + mIat.getParameter(SpeechConstant.LANGUAGE));

        // 此处用于设置dialog中不显示错误码信息
        //mIat.setParameter("view_tips_plain","false");

        // 设置语音前端点:静音超时时间，即用户多长时间不说话则当做超时处理
        mIat.setParameter(SpeechConstant.VAD_BOS, "4000");
        // 动态修正功能
        mIat.setParameter("dwa", "wpgs");

        // 设置语音后端点:后端点静音检测时间，即用户停止说话多长时间内即认为不再输入， 自动停止录音
        mIat.setParameter(SpeechConstant.VAD_EOS, "4000");

        // 设置标点符号,设置为"0"返回结果无标点,设置为"1"返回结果有标点
        mIat.setParameter(SpeechConstant.ASR_PTT, "1");

        // 设置音频保存路径，保存音频格式支持pcm、wav，设置路径为sd卡请注意WRITE_EXTERNAL_STORAGE权限
        mIat.setParameter(SpeechConstant.AUDIO_FORMAT, "wav");
        mIat.setParameter(SpeechConstant.ASR_AUDIO_PATH, Environment.getExternalStorageDirectory() + "/msc/iat.wav");
    }

    private void toast(String str) {
        mToast.setText(str);
        mToast.show();
    }
    private void vibrate(int time) {
//        if( time == null ) time = 100 ;
        mvibrator.vibrate( time);
    }

    private void requestPermissions() {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                int permission = ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
                if (permission != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, new String[]{
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            Manifest.permission.LOCATION_HARDWARE,
                            Manifest.permission.READ_PHONE_STATE,
                            Manifest.permission.WRITE_SETTINGS,
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.RECORD_AUDIO,
//                            Manifest.permission.READ_CONTACTS
                    }, 0x0010);
                }

                if (permission != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, new String[]{
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    }, 0x0010);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

}