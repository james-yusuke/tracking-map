package com.tracking.familyorbit

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class ParentSmokeTest {
    private val localNetworkPermission = LocalNetworkPermissionRule("com.tracking.familyorbit")
    private val compose: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity> =
        createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(localNetworkPermission).around(compose)

    @Test
    fun loginUsesOnlyRealAccountFlow() {
        compose.onNodeWithText("Family Orbit").assertIsDisplayed()
        compose.onNodeWithText("メールアドレス").assertIsDisplayed()
        compose.onNodeWithText("パスワード").assertIsDisplayed()
        compose.onAllNodesWithText("デモ画面を確認").assertCountEquals(0)
        compose.onAllNodesWithText("佐藤ファミリー").assertCountEquals(0)
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
