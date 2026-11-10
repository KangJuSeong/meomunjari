package com.example.meomunjari.di

import android.content.Context
import com.example.meomunjari.core.Gateways
import com.example.meomunjari.data.Repositories
import com.example.meomunjari.data.mock.MockAssetLoader
import com.example.meomunjari.data.mock.toRepositories
import com.example.meomunjari.domain.TagNormalizer
import com.example.meomunjari.trip.TripStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/** 수동 DI. 앱 전체에서 하나. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val repos: Repositories = MockAssetLoader.load(context.applicationContext).toRepositories()
    val tags = TagNormalizer(repos.reference.tags)

    /** 시연 가속 배율 (1 = 실제 시간). */
    val timeScale = MutableStateFlow(1)

    val gateways: Gateways = GatewayFactory.create(
        context = context.applicationContext,
        scope = appScope,
        timeScale = timeScale,
        spotsProvider = { repos.spots.spots.value },
    )

    val trip = TripStore(appScope, gateways, repos, tags, timeScale)

    private var started = false

    fun start() {
        if (started) return
        started = true
        gateways.start()
        trip.start()
    }

    fun stop() {
        if (!started) return
        started = false
        trip.stop()
        gateways.stop()
    }
}
