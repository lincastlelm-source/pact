package com.pact.coach.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pact.coach.domain.model.CoachIntensity
import com.pact.coach.domain.model.CoachPersonality
import com.pact.coach.domain.model.EnforcementLevel
import com.pact.coach.domain.model.ReflectionFrequency
import com.pact.coach.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.DayOfWeek

/**
 * App preferences, in DataStore rather than the Room database because they are small, read
 * constantly and have no relational shape.
 *
 * Reads are defensive: a corrupted preferences file yields defaults rather than crashing the app
 * on launch, which would otherwise lock the user out of their own data.
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pact_settings")

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val use24HourClock: Boolean = true,
    val firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,

    val defaultEnforcement: EnforcementLevel = EnforcementLevel.COACH,
    val defaultSoundUri: String? = null,
    val defaultSoundEnabled: Boolean = true,
    val defaultVibrationEnabled: Boolean = true,
    val defaultPreAlertMinutes: Int = 0,
    val defaultRecoveryMinutes: Int = 30,

    val coachPersonality: CoachPersonality = CoachPersonality.SUPPORTIVE,
    val coachIntensity: CoachIntensity = CoachIntensity.NORMAL,
    val reflectionFrequency: ReflectionFrequency = ReflectionFrequency.SOMETIMES,

    val onboardingComplete: Boolean = false,
    val demoDataLoaded: Boolean = false,
    val lastWeeklyReviewEpochDay: Long = 0L,
    /** Set once the user has been told about exact-alarm and battery settings. */
    val alarmHelpSeen: Boolean = false,
)

class SettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> = context.dataStore.data
        .catch { error ->
            // A damaged preferences file must not be fatal.
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs ->
            AppSettings(
                themeMode = prefs[Keys.THEME].toEnumOr(ThemeMode.SYSTEM),
                use24HourClock = prefs[Keys.USE_24H] ?: true,
                firstDayOfWeek = prefs[Keys.FIRST_DAY]?.let { runCatching { DayOfWeek.of(it) }.getOrNull() }
                    ?: DayOfWeek.MONDAY,
                defaultEnforcement = prefs[Keys.DEFAULT_ENFORCEMENT].toEnumOr(EnforcementLevel.COACH),
                defaultSoundUri = prefs[Keys.DEFAULT_SOUND],
                defaultSoundEnabled = prefs[Keys.DEFAULT_SOUND_ON] ?: true,
                defaultVibrationEnabled = prefs[Keys.DEFAULT_VIBRATION_ON] ?: true,
                defaultPreAlertMinutes = prefs[Keys.DEFAULT_PREALERT] ?: 0,
                defaultRecoveryMinutes = prefs[Keys.DEFAULT_RECOVERY] ?: 30,
                coachPersonality = prefs[Keys.PERSONALITY].toEnumOr(CoachPersonality.SUPPORTIVE),
                coachIntensity = prefs[Keys.INTENSITY].toEnumOr(CoachIntensity.NORMAL),
                reflectionFrequency = prefs[Keys.REFLECTION].toEnumOr(ReflectionFrequency.SOMETIMES),
                onboardingComplete = prefs[Keys.ONBOARDING_DONE] ?: false,
                demoDataLoaded = prefs[Keys.DEMO_LOADED] ?: false,
                lastWeeklyReviewEpochDay = prefs[Keys.LAST_REVIEW]?.toLong() ?: 0L,
                alarmHelpSeen = prefs[Keys.ALARM_HELP_SEEN] ?: false,
            )
        }

    suspend fun setTheme(mode: ThemeMode) = put(Keys.THEME, mode.name)
    suspend fun setUse24Hour(value: Boolean) = put(Keys.USE_24H, value)
    suspend fun setFirstDayOfWeek(day: DayOfWeek) = put(Keys.FIRST_DAY, day.value)
    suspend fun setDefaultEnforcement(level: EnforcementLevel) = put(Keys.DEFAULT_ENFORCEMENT, level.name)
    suspend fun setDefaultSoundEnabled(value: Boolean) = put(Keys.DEFAULT_SOUND_ON, value)
    suspend fun setDefaultVibrationEnabled(value: Boolean) = put(Keys.DEFAULT_VIBRATION_ON, value)
    suspend fun setDefaultPreAlertMinutes(value: Int) = put(Keys.DEFAULT_PREALERT, value)
    suspend fun setDefaultRecoveryMinutes(value: Int) = put(Keys.DEFAULT_RECOVERY, value)
    suspend fun setPersonality(value: CoachPersonality) = put(Keys.PERSONALITY, value.name)
    suspend fun setIntensity(value: CoachIntensity) = put(Keys.INTENSITY, value.name)
    suspend fun setReflectionFrequency(value: ReflectionFrequency) = put(Keys.REFLECTION, value.name)
    suspend fun setOnboardingComplete(value: Boolean) = put(Keys.ONBOARDING_DONE, value)
    suspend fun setDemoDataLoaded(value: Boolean) = put(Keys.DEMO_LOADED, value)
    suspend fun setAlarmHelpSeen(value: Boolean) = put(Keys.ALARM_HELP_SEEN, value)
    suspend fun setLastWeeklyReview(epochDay: Long) = put(Keys.LAST_REVIEW, epochDay.toInt())

    suspend fun setDefaultSound(uri: String?) {
        context.dataStore.edit { prefs ->
            if (uri == null) prefs.remove(Keys.DEFAULT_SOUND) else prefs[Keys.DEFAULT_SOUND] = uri
        }
    }

    private suspend fun put(key: Preferences.Key<String>, value: String) {
        context.dataStore.edit { it[key] = value }
    }

    private suspend fun put(key: Preferences.Key<Boolean>, value: Boolean) {
        context.dataStore.edit { it[key] = value }
    }

    private suspend fun put(key: Preferences.Key<Int>, value: Int) {
        context.dataStore.edit { it[key] = value }
    }

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val USE_24H = booleanPreferencesKey("use_24_hour")
        val FIRST_DAY = intPreferencesKey("first_day_of_week")
        val DEFAULT_ENFORCEMENT = stringPreferencesKey("default_enforcement")
        val DEFAULT_SOUND = stringPreferencesKey("default_sound_uri")
        val DEFAULT_SOUND_ON = booleanPreferencesKey("default_sound_on")
        val DEFAULT_VIBRATION_ON = booleanPreferencesKey("default_vibration_on")
        val DEFAULT_PREALERT = intPreferencesKey("default_prealert_minutes")
        val DEFAULT_RECOVERY = intPreferencesKey("default_recovery_minutes")
        val PERSONALITY = stringPreferencesKey("coach_personality")
        val INTENSITY = stringPreferencesKey("coach_intensity")
        val REFLECTION = stringPreferencesKey("reflection_frequency")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_complete")
        val DEMO_LOADED = booleanPreferencesKey("demo_data_loaded")
        val LAST_REVIEW = intPreferencesKey("last_weekly_review_day")
        val ALARM_HELP_SEEN = booleanPreferencesKey("alarm_help_seen")
    }
}

private inline fun <reified T : Enum<T>> String?.toEnumOr(default: T): T =
    if (this == null) default else enumValues<T>().firstOrNull { it.name == this } ?: default
