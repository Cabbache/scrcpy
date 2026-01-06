package com.genymobile.scrcpy;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.telephony.TelephonyManager;

import com.genymobile.scrcpy.util.Ln;

import java.io.PipedInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * Injects audio from the client computer into the Android device's microphone
 * using AudioPolicy APIs via reflection.
 *
 * Based on: https://github.com/Genymobile/scrcpy/issues/3880#issuecomment-1595722119
 */
public final class AudioInjector {

    private AudioInjector() {
    }

    private static AudioAttributes createAudioAttributes(int capturePreset) throws Exception {
        AudioAttributes.Builder audioAttributesBuilder = new AudioAttributes.Builder();
        Method setCapturePresetMethod =
            audioAttributesBuilder.getClass().getDeclaredMethod("setCapturePreset", int.class);
        setCapturePresetMethod.invoke(audioAttributesBuilder, capturePreset);
        return audioAttributesBuilder.build();
    }

    /**
     * Injects audio from a PipedInputStream into the device's microphone.
     *
     * @param pis The PipedInputStream containing PCM audio data to inject
     * @throws Exception if audio injection setup fails
     */
    public static void injectAudio(PipedInputStream pis) throws Exception {
        Context systemContext = Workarounds.getSystemContext();
        Objects.requireNonNull(systemContext);

        // Detect if there's an active phone call
        boolean inCall = false;
        try {
            TelephonyManager telephonyManager =
                (TelephonyManager) systemContext.getSystemService(Context.TELEPHONY_SERVICE);
            if (telephonyManager != null) {
                int callState = telephonyManager.getCallState();
                inCall = (callState == TelephonyManager.CALL_STATE_OFFHOOK);
                Ln.i("Call state: " + callState + " (OFFHOOK=" + TelephonyManager.CALL_STATE_OFFHOOK + ")");
                Ln.i("In call: " + inCall);
            }
        } catch (Exception e) {
            Ln.w("Could not check call state", e);
        }

        // var audioMixRuleBuilder = new AudioMixingRule.Builder();
        @SuppressLint("PrivateApi")
        Class<?> audioMixRuleBuilderClass =
                        Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
        Object audioMixRuleBuilder = audioMixRuleBuilderClass.getDeclaredConstructor().newInstance();

        try {
            // Added in Android 13, but previous versions don't work because lack of permission.
            // audioMixRuleBuilder.setTargetMixRole(MIX_ROLE_INJECTOR);
            Method setTargetMixRoleMethod =
                            audioMixRuleBuilder.getClass().getDeclaredMethod("setTargetMixRole", int.class);
            int MIX_ROLE_INJECTOR = 1;
            setTargetMixRoleMethod.invoke(audioMixRuleBuilder, MIX_ROLE_INJECTOR);
        } catch (Exception ignored) {
        }

        Method addMixRuleMethod = audioMixRuleBuilder.getClass()
                        .getDeclaredMethod("addMixRule", int.class, Object.class);
        int RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET = 0x1 << 1;

        // Add mix rules for various capture presets to intercept all microphone capture
        addMixRuleMethod.invoke(audioMixRuleBuilder, RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET,
                                                        createAudioAttributes(MediaRecorder.AudioSource.DEFAULT));
        addMixRuleMethod.invoke(audioMixRuleBuilder, RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET,
                                                        createAudioAttributes(MediaRecorder.AudioSource.MIC));
        addMixRuleMethod.invoke(audioMixRuleBuilder, RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET,
                                                        createAudioAttributes(
                                                                        MediaRecorder.AudioSource.VOICE_COMMUNICATION));
        addMixRuleMethod.invoke(audioMixRuleBuilder, RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET,
                                                        createAudioAttributes(MediaRecorder.AudioSource.UNPROCESSED));

        // var audioMixingRule = audioMixRuleBuilder.build();
        Method audioMixRuleBuildMethod = audioMixRuleBuilder.getClass().getDeclaredMethod("build");
        Object audioMixingRule = audioMixRuleBuildMethod.invoke(audioMixRuleBuilder);
        Objects.requireNonNull(audioMixingRule);

        // var audioMixBuilder = new AudioMix.Builder(audioMixingRule);
        @SuppressLint("PrivateApi")
        Class<?> audioMixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix$Builder");
        Constructor audioMixBuilderConstructor =
                        audioMixBuilderClass.getDeclaredConstructor(audioMixingRule.getClass());
        Object audioMixBuilder = audioMixBuilderConstructor.newInstance(audioMixingRule);

        Object audioFormat = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(48000)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build();

        // audioMixBuilder.setFormat(audioFormat);
        Method setFormatMethod =
            audioMixBuilder.getClass().getDeclaredMethod("setFormat", AudioFormat.class);
        setFormatMethod.invoke(audioMixBuilder, audioFormat);

        // audioMixBuilder.setRouteFlags(ROUTE_FLAG_LOOP_BACK);
        Method setRouteFlagsMethod =
            audioMixBuilder.getClass().getDeclaredMethod("setRouteFlags", int.class);
        int ROUTE_FLAG_LOOP_BACK = 0x1 << 1;
        setRouteFlagsMethod.invoke(audioMixBuilder, ROUTE_FLAG_LOOP_BACK);

        if (inCall && Build.VERSION.SDK_INT >= 30) {
            try {
                Method setCallRedirectionMethod =
                    audioMixBuilder.getClass().getDeclaredMethod("setCallRedirection", boolean.class);
                setCallRedirectionMethod.invoke(audioMixBuilder, true);
                Ln.i("Call redirection enabled on AudioMix");
            } catch (Exception e) {
                Ln.w("Could not enable call redirection on AudioMix: " + e.getMessage());
            }
        }

        // var audioMix = audioMixBuilder.build();
        Method audioMixBuildMethod = audioMixBuilder.getClass().getDeclaredMethod("build");
        Object audioMix = audioMixBuildMethod.invoke(audioMixBuilder);
        Objects.requireNonNull(audioMix);

        // var audioPolicyBuilder = new AudioPolicy.Builder(systemContext);
        @SuppressLint("PrivateApi")
        Class<?> audioPolicyBuilderClass =
                        Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
        Constructor audioPolicyBuilderConstructor =
                        audioPolicyBuilderClass.getDeclaredConstructor(Context.class);
        Object audioPolicyBuilder = audioPolicyBuilderConstructor.newInstance(systemContext);

        // audioPolicyBuilder.addMix(audioMix);
        Method addMixMethod =
                        audioPolicyBuilder.getClass().getDeclaredMethod("addMix", audioMix.getClass());
        addMixMethod.invoke(audioPolicyBuilder, audioMix);

        // var audioPolicy = audioPolicyBuilder.build();
        Method audioPolicyBuildMethod = audioPolicyBuilder.getClass().getDeclaredMethod("build");
        Object audioPolicy = audioPolicyBuildMethod.invoke(audioPolicyBuilder);
        Objects.requireNonNull(audioPolicy);

        Object audioManager = (AudioManager) systemContext.getSystemService(AudioManager.class);

        // audioManager.registerAudioPolicy(audioPolicy);
        Method registerAudioPolicyMethod = audioManager.getClass()
                        .getDeclaredMethod("registerAudioPolicy", audioPolicy.getClass());
        // noinspection DataFlowIssue
        int result = (int) registerAudioPolicyMethod.invoke(audioManager, audioPolicy);

        if (result != 0) {
            Ln.d("registerAudioPolicy failed");
            return;
        }

        // var audioTrack = audioPolicy.createAudioTrackSource(audioMix);
        Method createAudioTrackSourceMethod = audioPolicy.getClass()
                        .getDeclaredMethod("createAudioTrackSource", audioMix.getClass());
        AudioTrack audioTrack = (AudioTrack) createAudioTrackSourceMethod.invoke(audioPolicy, audioMix);
        Objects.requireNonNull(audioTrack);

        int state = audioTrack.getState();
        Ln.i("AudioTrack state: " + state + " (INITIALIZED=" + AudioTrack.STATE_INITIALIZED + ")");
        if (state != AudioTrack.STATE_INITIALIZED) {
            Ln.e("AudioTrack not initialized properly, state=" + state);
            throw new Exception("AudioTrack creation failed with state " + state);
        }

        audioTrack.play();

        int playState = audioTrack.getPlayState();
        Ln.i("AudioTrack playback state: " + playState + " (PLAYING=" + AudioTrack.PLAYSTATE_PLAYING + ")");
        if (playState != AudioTrack.PLAYSTATE_PLAYING) {
            Ln.e("AudioTrack failed to start playing, playState=" + playState);
            throw new Exception("AudioTrack playback failed to start, playState=" + playState);
        }

        Ln.i("successfull: " + inCall);

        new Thread(() -> {
            byte[] audioBuffer = new byte[4096];
            int writeAttempts = 0;
            int consecutiveErrors = 0;

            while (true) {
                try {
                    int bytesRead = pis.read(audioBuffer);
                    if (bytesRead <= 0) {
                        break;
                    }

                    int written = audioTrack.write(audioBuffer, 0, bytesRead);
                    writeAttempts++;

                    if (written < 0) {
                        consecutiveErrors++;
                        Ln.e("AudioTrack write error: " + written + " (attempt " + writeAttempts + ")");

                        if (consecutiveErrors >= 5) {
                            Ln.e("Multiple consecutive write errors");
                            break;
                        }
                    } else {
                        consecutiveErrors = 0;
                    }
                } catch (Exception e) {
                    Ln.e("Audio injection error", e);
                    break;
                }
            }

            Ln.i("Audio injection thread ending");
            audioTrack.stop();
            audioTrack.release();
        }, "client-audio-injector").start();
    }
}
