package com.android.cts.media;

import android.util.Log;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
public class ExampleUnitTest {
    @Test
    public void addition_isCorrect() throws Exception {
        assertEquals(4, 2 + 2);

        String regx = "^[0-9]{2}.mp4$";

        String [] names={"91.mp4","2.mp4","23."};

       for(String name:names){
           System.out.printf(name+":"+name.matches(regx));
//           Log.i("CTS", name+":"+name.matches(regx));
       }
    }
}