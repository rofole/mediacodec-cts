package com.android.cts;

import android.media.MediaCodecInfo;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.widget.TextView;

import com.android.cts.R;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

    // Example of a call to a native method
    TextView tv = (TextView) findViewById(R.id.sample_text);
//    tv.setText(stringFromJNI());




        new Thread() {
            public void run() {
                AsynExtractDecodeEditEncodeMuxTest test = new AsynExtractDecodeEditEncodeMuxTest();
//                ATranscodeTest test = new ATranscodeTest();
                test.setContext(MainActivity.this);
                try {
                    test.testExtractDecodeEditEncodeMuxAudioVideo();
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        }.start();
    }

    /**
     * A native method that is implemented by the 'native-lib' native library,
     * which is packaged with this application.
     */
//    public native String stringFromJNI();

    // Used to load the 'native-lib' library on application startup.
//    static {
//        System.loadLibrary("native-lib");
//    }
}
