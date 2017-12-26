package at.favre.lib.securepref;

import android.content.SharedPreferences;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;

import org.junit.runner.RunWith;

/**
 * Instrumented test, which will execute on an Android device.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
public class SecureSharedPreferencesTest extends ASecureSharedPreferencesTest {
    protected SharedPreferences create(String name, char[] pw) {
        return Armadillo.create(InstrumentationRegistry.getTargetContext(), name)
                .encryptionFingerprint(InstrumentationRegistry.getTargetContext())
                .password(pw)
                .build();
    }
}
