package com.android.cts.media;

import android.content.Context;
import android.os.Environment;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.util.Log;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

import static org.junit.Assert.*;

/**
 * Instrumented test, which will execute on an Android device.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
public class AExampleInstrumentedTest {
    private static final File OUTPUT_FILENAME_DIR = Environment.getExternalStorageDirectory();
    @Test
    public void useAppContext() throws Exception {
        // Context of the app under test.
        Context appContext = InstrumentationRegistry.getTargetContext();

        assertEquals("com.android.cts.media", appContext.getPackageName());
    }

    public Context getContext(){
        return InstrumentationRegistry.getTargetContext();
    }

    public static String generateProjectId() {
        String dbPath=OUTPUT_FILENAME_DIR.getAbsolutePath()+"/cts";
        File baseDir = new File(dbPath);
        if (!baseDir.isDirectory() || !baseDir.exists()) {
            if (!baseDir.mkdirs() && !baseDir.isDirectory()) {
                throw new RuntimeException("cannot create Project HomeDir");
            }
        }

        int lastIndex = 0;
        String regx = "^[0-9]{3}.mp4$";
        File[] projectDires = baseDir.listFiles();
        for (File projectDir : projectDires) {
            if (!projectDir.isFile())
                continue;
            try {
                if (projectDir.getName().matches(regx)) {
                    int index = Integer.valueOf(projectDir.getName().substring(0, 3));
                    if (index > lastIndex) {
                        lastIndex = index;
                    }
                }
            } catch (Exception e) {
                Log.e("CTS", "generateProjectId error", e);
            }
        }
        lastIndex++;
        lastIndex = lastIndex % 100;
        String id = String.format("%03d", lastIndex) ;
        return dbPath+"/"+id;
    }
}
