package dev.shield.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.shield.core.checks.SelfTestRunner
import dev.shield.core.filter.FilterEngine
import dev.shield.core.vpn.VpnController
import dev.shield.data.db.ShieldDatabase
import dev.shield.data.repo.QueryRecorder
import dev.shield.data.repo.RulesRepository
import dev.shield.data.repo.SettingsRepository

/** Access to singletons for instrumented end-to-end tests (no production use). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TestHooks {
    fun controller(): VpnController
    fun settings(): SettingsRepository
    fun rules(): RulesRepository
    fun selfTest(): SelfTestRunner
    fun filter(): FilterEngine
    fun database(): ShieldDatabase
    fun recorder(): QueryRecorder
}
