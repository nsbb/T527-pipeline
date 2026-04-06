package com.t527.wav2vecdemo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import com.t527.wav2vecdemo.conformer.AwConformerJni;
import com.t527.wav2vecdemo.conformer.ConformerDecoder;
import com.t527.wav2vecdemo.conformer.SileroVad;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONObject;

public class VadPipelineService extends Service {
    private static final String TAG = "VadPipelineService";
    private static final String CHANNEL_ID = "vad_pipeline_channel";
    private static final int NOTIF_ID = 1;

    public static final String ACTION_START_STT = "com.t527.vad_pipeline.ACTION_START_STT";
    public static final String ACTION_MIC_GRANTED = "com.t527.vad_pipeline.ACTION_MIC_GRANTED";
    public static final String ACTION_RELOAD_CONFIG = "com.t527.vad_pipeline.ACTION_RELOAD_CONFIG";

    private static final String CONFIG_SDCARD = "/sdcard/t527_vad_service/config.json";
    private static final int VAD_WINDOW = 512;

    // Config values (loaded from JSON)
    private float CONF_MEL_SCALE = 0.025880949571728706f;
    private int CONF_MEL_ZP = 84;
    private int SEQ_OUT = 76;
    private int STRIDE_OUT = 62;
    private float WK_THRESHOLD = 0.40f;
    private float VAD_THRESHOLD = 0.5f;
    private int MAX_SPEECH_MS = 10000;
    private int SILENCE_TIMEOUT_MS = 800;
    private float MIN_SPEECH_SECONDS = 0.5f;
    private int SR_MIC = 48000;
    private int SR_MODEL = 16000;
    private int WK_WINDOW_48K = 72000;
    private int WK_HOP_48K = 24000;
    private int FLUSH_AFTER_WAKE_MS = 1000;

    private AwConformerJni mJni;
    private ConformerDecoder mDecoder;
    private SileroVad mVad;
    private volatile boolean mRunning = false;
    private volatile boolean mSttRequested = false;
    private volatile boolean mMicGranted = false;
    private Handler mHandler;
    private Thread mPipelineThread;

    private float wkInpScale, wkOutScale;
    private int wkInpZp, wkOutZp;

    // Trigger counter
    private int mWakeCount = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");
        mHandler = new Handler(Looper.getMainLooper());

        createNotificationChannel();
        Notification notif = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("음성 AI 서비스")
                .setContentText("'하이 원더' 대기 중")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build();
        startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);

        initModels();
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "VAD Pipeline", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("음성 AI 백그라운드 서비스");
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private String copyAsset(String dir, String name) {
        File out = new File(getFilesDir(), dir.replace("/", "_") + "_" + name);
        if (out.exists() && out.length() > 0) return out.getAbsolutePath();
        out.getParentFile().mkdirs();
        try (InputStream in = getAssets().open(dir + "/" + name);
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[1024 * 1024];
            int len;
            while ((len = in.read(buf)) > 0) fos.write(buf, 0, len);
        } catch (IOException e) {
            Log.e(TAG, "copy failed: " + dir + "/" + name, e);
            return null;
        }
        return out.getAbsolutePath();
    }

    private void loadWakewordMeta(String metaPath) {
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(metaPath));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            JSONObject json = new JSONObject(sb.toString());
            JSONObject inp = json.getJSONObject("Inputs").getJSONObject(
                    json.getJSONObject("Inputs").keys().next()).getJSONObject("quantize");
            wkInpScale = (float) inp.getDouble("scale");
            wkInpZp = inp.getInt("zero_point");
            JSONObject outp = json.getJSONObject("Outputs").getJSONObject(
                    json.getJSONObject("Outputs").keys().next()).getJSONObject("quantize");
            wkOutScale = (float) outp.getDouble("scale");
            wkOutZp = outp.getInt("zero_point");
        } catch (Exception e) {
            Log.e(TAG, "WK meta load failed", e);
            wkInpScale = 0.063f; wkInpZp = 218; wkOutScale = 0.0077f; wkOutZp = 128;
        }
    }

    private void loadConfig() {
        String jsonStr = null;
        File sdcard = new File(CONFIG_SDCARD);
        if (sdcard.exists()) {
            try {
                BufferedReader br = new BufferedReader(new FileReader(sdcard));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                jsonStr = sb.toString();
                Log.d(TAG, "Config loaded from sdcard: " + CONFIG_SDCARD);
            } catch (Exception e) {
                Log.e(TAG, "sdcard config read failed", e);
            }
        }
        if (jsonStr == null) {
            try {
                BufferedReader br = new BufferedReader(new InputStreamReader(getAssets().open("config.json")));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                jsonStr = sb.toString();
                Log.d(TAG, "Config loaded from assets");
            } catch (Exception e) {
                Log.e(TAG, "asset config read failed, using defaults", e);
                return;
            }
        }
        try {
            JSONObject json = new JSONObject(jsonStr);
            JSONObject wk = json.optJSONObject("wakeword");
            if (wk != null) {
                WK_THRESHOLD = (float) wk.optDouble("threshold", WK_THRESHOLD);
            }
            JSONObject vad = json.optJSONObject("vad");
            if (vad != null) {
                VAD_THRESHOLD = (float) vad.optDouble("threshold", VAD_THRESHOLD);
                SILENCE_TIMEOUT_MS = vad.optInt("silence_timeout_ms", SILENCE_TIMEOUT_MS);
                MAX_SPEECH_MS = vad.optInt("max_speech_ms", MAX_SPEECH_MS);
                MIN_SPEECH_SECONDS = (float) vad.optDouble("min_speech_seconds", MIN_SPEECH_SECONDS);
            }
            JSONObject audio = json.optJSONObject("audio");
            if (audio != null) {
                SR_MIC = audio.optInt("sample_rate_mic", SR_MIC);
                SR_MODEL = audio.optInt("sample_rate_model", SR_MODEL);
                FLUSH_AFTER_WAKE_MS = audio.optInt("flush_after_wake_ms", FLUSH_AFTER_WAKE_MS);
                WK_WINDOW_48K = SR_MIC * 3 / 2;
                WK_HOP_48K = SR_MIC / 2;
            }
            JSONObject stt = json.optJSONObject("stt");
            if (stt != null) {
                CONF_MEL_SCALE = (float) stt.optDouble("mel_scale", CONF_MEL_SCALE);
                CONF_MEL_ZP = stt.optInt("mel_zp", CONF_MEL_ZP);
                SEQ_OUT = stt.optInt("seq_out", SEQ_OUT);
                STRIDE_OUT = stt.optInt("stride_out", STRIDE_OUT);
            }
            Log.d(TAG, String.format("Config: wk_th=%.2f, vad_th=%.2f, silence=%dms, flush=%dms, min_speech=%.1fs",
                    WK_THRESHOLD, VAD_THRESHOLD, SILENCE_TIMEOUT_MS, FLUSH_AFTER_WAKE_MS, MIN_SPEECH_SECONDS));
        } catch (Exception e) {
            Log.e(TAG, "Config parse failed, using defaults", e);
        }
    }

    private void initModels() {
        loadConfig();
        String wkNb = copyAsset("models/Wakeword", "network_binary.nb");
        String wkMeta = copyAsset("models/Wakeword", "nbg_meta.json");

        loadWakewordMeta(wkMeta);

        AwConformerJni.initNpu();
        mJni = new AwConformerJni();
        boolean wkOk = mJni.initWakeword(wkNb);

        Log.d(TAG, "Wakeword: " + wkOk + " (VT-only mode)");

        if (wkOk) {
            mRunning = true;
            showToast("음성 AI 서비스 대기 중 (마이크 대기)");
        } else {
            showToast("모델 초기화 실패");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_MIC_GRANTED.equals(action)) {
                Log.d(TAG, "BR: mic granted, starting VT");
                if (!mMicGranted) {
                    mMicGranted = true;
                    mPipelineThread = new Thread(this::pipelineLoop);
                    mPipelineThread.start();
                    showToast("마이크 획득 - VT 시작");
                }
            } else if (ACTION_START_STT.equals(action)) {
                Log.d(TAG, "BR: STT requested");
                mSttRequested = true;
            } else if (ACTION_RELOAD_CONFIG.equals(action)) {
                loadConfig();
                showToast("Config 리로드 완료");
            }
        }
        return START_STICKY;
    }

    private float[] resample48to16(short[] pcm48k, int len) {
        int newLen = len / 3;
        float[] out = new float[newLen];
        for (int i = 0; i < newLen; i++) {
            float srcIdx = i * 3.0f;
            int idx0 = (int) srcIdx;
            float frac = srcIdx - idx0;
            int idx1 = Math.min(idx0 + 1, len - 1);
            out[i] = ((1 - frac) * pcm48k[idx0] + frac * pcm48k[idx1]) / 32768.0f;
        }
        return out;
    }

    private void pipelineLoop() {
        int bufSize = Math.max(
                AudioRecord.getMinBufferSize(SR_MIC, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
                SR_MIC * 2 * 2);

        AudioRecord recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
                SR_MIC, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
        recorder.startRecording();

        short[] ringBuffer = new short[WK_WINDOW_48K];
        int ringPos = 0;
        boolean bufferFull = false;

        Log.d(TAG, "Pipeline loop started");

        while (mRunning) {
            // BR로 STT 요청이 들어왔으면 바로 STT 실행
            if (mSttRequested) {
                mSttRequested = false;
                Log.d(TAG, "BR trigger: running STT now");
                showToast("STT 시작...");
                runStt(recorder);
                java.util.Arrays.fill(ringBuffer, (short) 0);
                ringPos = 0; bufferFull = false;
                continue;
            }

            // === VT MODE: 0.5초마다 wakeword 체크 ===
            short[] chunk = new short[WK_HOP_48K];
            int read = recorder.read(chunk, 0, WK_HOP_48K);
            if (read <= 0) continue;

            for (int i = 0; i < read; i++) {
                ringBuffer[ringPos % WK_WINDOW_48K] = chunk[i];
                ringPos++;
            }
            if (ringPos >= WK_WINDOW_48K) bufferFull = true;
            if (!bufferFull) continue;

            short[] audio48k = new short[WK_WINDOW_48K];
            int start = ringPos % WK_WINDOW_48K;
            for (int i = 0; i < WK_WINDOW_48K; i++)
                audio48k[i] = ringBuffer[(start + i) % WK_WINDOW_48K];

            float[] audio16k = resample48to16(audio48k, WK_WINDOW_48K);

            long tWkMel0 = System.currentTimeMillis();
            byte[] wkMel = mJni.computeWakewordMel(audio16k, wkInpScale, wkInpZp);
            long tWkMel1 = System.currentTimeMillis();
            float[] wkProbs = mJni.runWakeword(wkMel, wkOutScale, wkOutZp);
            long tWkNpu1 = System.currentTimeMillis();

            if (wkProbs == null || wkProbs.length < 2) continue;
            float wakeProb = wkProbs[1];

            if (wakeProb >= WK_THRESHOLD) {
                mWakeCount++;
                long vtMel = tWkMel1 - tWkMel0;
                long vtNpu = tWkNpu1 - tWkMel1;
                Log.d(TAG, String.format("[Wake #%d] Wake detected (count=%d) — prob=%.2f (mel=%dms, npu=%dms) — STT save OFF, staying in wake mode",
                        mWakeCount, mWakeCount, wakeProb, vtMel, vtNpu));
                showToast(String.format("'하이 원더' 감지! (#%d)\nprob=%.2f (mel %dms, npu %dms)", mWakeCount, wakeProb, vtMel, vtNpu));
                java.util.Arrays.fill(ringBuffer, (short) 0);
                ringPos = 0; bufferFull = false;
                try { Thread.sleep(2000); } catch (InterruptedException e) {}
            }
        }

        recorder.stop();
        recorder.release();
        Log.d(TAG, "Pipeline loop stopped");
    }

    private void runStt(AudioRecord recorder) {
        // wakeword 꼬리 음성 제거
        short[] flush = new short[SR_MIC * FLUSH_AFTER_WAKE_MS / 1000];
        recorder.read(flush, 0, flush.length);

        mVad.resetState();
        List<float[]> speechChunks = new ArrayList<>();
        int silenceFrames = 0;
        int speechFrames = 0;
        int vadFrameSize48k = VAD_WINDOW * 3;
        int maxFrames = MAX_SPEECH_MS * SR_MIC / 1000 / vadFrameSize48k;
        int silenceLimit = SILENCE_TIMEOUT_MS * SR_MODEL / 1000 / VAD_WINDOW;
        boolean speechStarted = false;
        long tSttStart = System.currentTimeMillis();

        while (mRunning && speechFrames < maxFrames) {
            short[] vadChunk48k = new short[vadFrameSize48k];
            int vadRead = recorder.read(vadChunk48k, 0, vadFrameSize48k);
            if (vadRead <= 0) continue;

            float[] vadAudio = resample48to16(vadChunk48k, vadRead);
            float vadProb = mVad.process(vadAudio);
            boolean isSpeech = vadProb >= VAD_THRESHOLD;

            if (isSpeech) {
                speechStarted = true;
                silenceFrames = 0;
                speechChunks.add(vadAudio);
                speechFrames++;
            } else if (speechStarted) {
                silenceFrames++;
                speechChunks.add(vadAudio);
                if (silenceFrames >= silenceLimit) break;
            } else {
                if (System.currentTimeMillis() - tSttStart > 9000) {
                    Log.d(TAG, "VAD: timeout, no speech");
                    showToast("(음성 없음)");
                    return;
                }
            }
        }

        if (!speechStarted || speechChunks.isEmpty()) {
            showToast("(음성 없음)");
            return;
        }

        int totalSamples = speechChunks.size() * VAD_WINDOW;
        float speechOnlyDuration = speechFrames * VAD_WINDOW / (float) SR_MODEL;
        if (speechOnlyDuration < 0.5f) {
            Log.d(TAG, String.format("VAD: speech only %.2fs (total %.2fs), skipping",
                    speechOnlyDuration, totalSamples / (float) SR_MODEL));
            return;
        }
        float speechDuration = totalSamples / (float) SR_MODEL;

        float[] fullAudio = new float[totalSamples];
        for (int i = 0; i < speechChunks.size(); i++) {
            System.arraycopy(speechChunks.get(i), 0, fullAudio, i * VAD_WINDOW,
                    Math.min(speechChunks.get(i).length, VAD_WINDOW));
        }
        Log.d(TAG, String.format("VAD: speech %.2fs (%d samples)", speechDuration, totalSamples));

        // STT 슬라이딩 윈도우
        long tMelTotal = 0, tNpuTotal = 0;
        int WINDOW_SAMPLES = SR_MODEL * 301 / 100;
        int STRIDE_SAMPLES = SR_MODEL * 250 / 100;
        List<int[]> allArgmax = new ArrayList<>();
        int numChunks = 0;

        int pos = 0;
        while (pos < totalSamples) {
            int end = Math.min(pos + WINDOW_SAMPLES, totalSamples);
            int chunkLen = end - pos;
            float[] chunkAudio = new float[chunkLen];
            System.arraycopy(fullAudio, pos, chunkAudio, 0, chunkLen);

            long tM0 = System.currentTimeMillis();
            byte[] mel = mJni.computeMel(chunkAudio, CONF_MEL_SCALE, CONF_MEL_ZP);
            long tM1 = System.currentTimeMillis();
            tMelTotal += (tM1 - tM0);

            long tN0 = System.currentTimeMillis();
            int[] argmax = mJni.runUint8(mel);
            long tN1 = System.currentTimeMillis();
            tNpuTotal += (tN1 - tN0);

            if (argmax != null) allArgmax.add(argmax);
            numChunks++;
            if (end >= totalSamples) break;
            pos += STRIDE_SAMPLES;
        }

        List<Integer> mergedIds = new ArrayList<>();
        for (int ci = 0; ci < allArgmax.size(); ci++) {
            int[] ids = allArgmax.get(ci);
            int useFrames = (ci < allArgmax.size() - 1) ? STRIDE_OUT : SEQ_OUT;
            for (int t = 0; t < useFrames && t < ids.length; t++) {
                mergedIds.add(ids[t]);
            }
        }
        int[] merged = new int[mergedIds.size()];
        for (int i = 0; i < merged.length; i++) merged[i] = mergedIds.get(i);

        String text = mDecoder.decode(merged);
        String resultText = text.isEmpty() ? "(인식 없음)" : text;

        Log.d(TAG, String.format("STT: [%s] (%.1fs, mel=%dms, npu=%dms, %d chunks)",
                resultText, speechDuration, tMelTotal, tNpuTotal, numChunks));

        showToast(String.format("%s\n(mel %dms, npu %dms, %d chunks)", resultText, tMelTotal, tNpuTotal, numChunks));
    }

    private void showToast(String msg) {
        mHandler.post(() -> {
            Log.d(TAG, "showToast: " + msg);
            try {
                WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                TextView tv = new TextView(this);
                tv.setText(msg);
                tv.setTextSize(32);
                tv.setTextColor(Color.WHITE);
                tv.setBackgroundColor(0xDD6A0DAD);
                tv.setPadding(48, 32, 48, 32);
                tv.setMaxLines(2);

                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        PixelFormat.TRANSLUCENT);
                params.gravity = Gravity.CENTER;
                params.y = 300;

                wm.addView(tv, params);
                mHandler.postDelayed(() -> {
                    try { wm.removeView(tv); } catch (Exception e) {}
                }, 5000);
            } catch (Exception e) {
                Log.e(TAG, "Overlay failed: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service onDestroy");
        mRunning = false;
        if (mJni != null) { mJni.releaseWakeword(); }
    }
}
