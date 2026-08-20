package com.tracking.familyorbit.link

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tracking.familyorbit.core.SecureTokenStore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class LinkSmokeTest {
    private val resetLinkState = ResetLinkStateRule()
    private val localNetworkPermission = LocalNetworkPermissionRule("com.tracking.familyorbit.link")
    private val compose: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity> =
        createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(resetLinkState).around(localNetworkPermission).around(compose)

    @Test
    fun pairingExplainsVisibleTracking() {
        compose.onNodeWithText("Family Orbit Link").assertIsDisplayed()
        compose.onNodeWithText("位置共有の状態は、いつでもこの画面で確認できます。接続コードによっては、アプリ内の停止操作が保護者設定で制限されます。").assertIsDisplayed()
    }

    @Test
    fun removedFamilyIsClearlyShown() {
        compose.activityRule.scenario.onActivity { FamilyRemovalState.mark(it) }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("家族から削除されました").assertIsDisplayed()
        compose.onNodeWithText("位置共有とメッセージ受信は停止しました。再び参加するには、保護者から新しい接続コードを受け取ってください。")
            .assertIsDisplayed()
    }

}

private class ResetLinkStateRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            SecureTokenStore(context, "child_device_token").clear()
            context.getSharedPreferences("family_orbit_link", 0).edit().clear().commit()
            base.evaluate()
        }
    }
}

private class LocalNetworkPermissionRule(private val packageName: String) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            if (Build.VERSION.SDK_INT >= 37) {
                InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                    packageName,
                    Manifest.permission.ACCESS_LOCAL_NETWORK,
                )
            }
            base.evaluate()
        }
    }
}
