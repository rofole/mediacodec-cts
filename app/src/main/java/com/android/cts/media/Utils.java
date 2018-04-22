package com.android.cts.media;

import android.os.Environment;
import android.util.Log;

import java.io.File;

/**
 * Created by guoxian on 2018/4/22.
 */

public class Utils {
    public static final String TAG="VNI_AE";
    public static String generateNextMp4FileName() {
        String dbPath= Environment.getExternalStorageDirectory().getAbsolutePath()+"/cts";
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
        lastIndex = lastIndex % 1000;
        String id = String.format("%03d", lastIndex) ;
        return dbPath+"/"+id+".mp4";
    }
}
