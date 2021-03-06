package ioio.examples.holiday

import android.Manifest
import androidx.test.espresso.Espresso
import androidx.test.espresso.assertion.ViewAssertions
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.moka.utils.Screenshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HolidayIOIOSmokeTest {

    @get:Rule
    var activityScenarioRule = ActivityScenarioRule(HolidayIOIOActivity::class.java)

    @get:Rule
    val grantPermissionRule: GrantPermissionRule = GrantPermissionRule.grant(
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.CAMERA,
            Manifest.permission.READ_EXTERNAL_STORAGE)

    @Test
    fun smokeTestSimplyStart() {
        Espresso.onView(ViewMatchers.withId(R.id.frequencySeekBar)).check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        Screenshot.takeScreenshot("smoke")
    }
}
