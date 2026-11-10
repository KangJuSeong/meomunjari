package com.example.meomunjari

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        // mock 플레이버는 applicationIdSuffix(".mock")가 붙는다.
        assertTrue(appContext.packageName.startsWith("com.example.meomunjari"))
    }
}
