package de.mm20.launcher2.weather

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.content.getSystemService
import androidx.work.*
import de.mm20.launcher2.database.AppDatabase
import de.mm20.launcher2.ktx.checkPermission
import de.mm20.launcher2.permissions.PermissionGroup
import de.mm20.launcher2.permissions.PermissionsManager
import de.mm20.launcher2.plugin.PluginRepository
import de.mm20.launcher2.plugin.PluginType
import de.mm20.launcher2.preferences.LatLon
import de.mm20.launcher2.preferences.weather.WeatherLocation
import de.mm20.launcher2.preferences.weather.WeatherSettings
import de.mm20.launcher2.weather.brightsky.BrightSkyProvider
import de.mm20.launcher2.weather.here.HereProvider
import de.mm20.launcher2.weather.metno.MetNoProvider
import de.mm20.launcher2.weather.openweathermap.OpenWeatherMapProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.*
import java.util.concurrent.TimeUnit

interface WeatherRepository {
    fun getProviders(): Flow<List<WeatherProviderInfo>>
    fun searchLocations(query: String): Flow<List<WeatherLocation>>

    fun getForecasts(limit: Int? = null): Flow<List<Forecast>>
    fun getDailyForecasts(): Flow<List<DailyForecast>>

    fun deleteForecasts()
}

internal class WeatherRepositoryImpl(
    private val context: Context,
    private val database: AppDatabase,
    private val settings: WeatherSettings,
    private val pluginRepository: PluginRepository,
) : WeatherRepository, KoinComponent {

    private val scope = CoroutineScope(Job() + Dispatchers.Default)


    private val permissionsManager: PermissionsManager by inject()

    private val hasLocationPermission = permissionsManager.hasPermission(PermissionGroup.Location)


    override fun getForecasts(limit: Int?): Flow<List<Forecast>> {
        return database.weatherDao().getForecasts(limit ?: 99999)
            .map { it.map { Forecast(it) } }
    }
    override fun getDailyForecasts(): Flow<List<DailyForecast>> {
        return database.weatherDao().getForecasts()
            .map { it.map { Forecast(it) } }
            .map {
                groupForecastsPerDay(it)
            }
    }

    override fun searchLocations(query: String): Flow<List<WeatherLocation>> {
        return settings.map {
            val provider = WeatherProvider.getInstance(it.provider)
            provider.findLocation(query)
        }
    }

    init {
        val weatherRequest =
            PeriodicWorkRequest.Builder(WeatherUpdateWorker::class.java, 60, TimeUnit.MINUTES)
                .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "weather",
            ExistingPeriodicWorkPolicy.KEEP, weatherRequest
        )

        scope.launch {
            hasLocationPermission.collectLatest {
                if (it) requestUpdate()
            }
        }
        scope.launch {
            settings.collectLatest {
                requestUpdate()
            }
        }
    }

    private fun groupForecastsPerDay(forecasts: List<Forecast>): List<DailyForecast> {
        val dailyForecasts = mutableListOf<DailyForecast>()
        val calendar = Calendar.getInstance()
        var currentDay = 0
        var currentDayForecasts: MutableList<Forecast> = mutableListOf()
        for (fc in forecasts) {
            calendar.timeInMillis = fc.timestamp
            if (currentDay != calendar.get(Calendar.DAY_OF_YEAR)) {
                if (currentDayForecasts.isNotEmpty()) {
                    dailyForecasts.add(
                        DailyForecast(
                            timestamp = currentDayForecasts.first().timestamp,
                            minTemp = currentDayForecasts.minByOrNull { it.temperature }?.temperature
                                ?: 0.0,
                            maxTemp = currentDayForecasts.maxByOrNull { it.temperature }?.temperature
                                ?: 0.0,
                            hourlyForecasts = currentDayForecasts
                        )
                    )
                    currentDayForecasts = mutableListOf()
                }
                currentDay = calendar.get(Calendar.DAY_OF_YEAR)
            }
            currentDayForecasts.add(fc)
        }
        if (currentDayForecasts.isNotEmpty()) {
            dailyForecasts.add(
                DailyForecast(
                    timestamp = currentDayForecasts.first().timestamp,
                    minTemp = currentDayForecasts.minByOrNull { it.temperature }?.temperature
                        ?: 0.0,
                    maxTemp = currentDayForecasts.maxByOrNull { it.temperature }?.temperature
                        ?: 0.0,
                    hourlyForecasts = currentDayForecasts
                )
            )
        }
        return dailyForecasts
    }


    private fun requestUpdate() {
        val weatherRequest = OneTimeWorkRequest.Builder(WeatherUpdateWorker::class.java)
            .addTag("weather")
            .build()
        WorkManager.getInstance(context).enqueue(weatherRequest)
    }

    override fun deleteForecasts() {
        scope.launch {
            withContext(Dispatchers.IO) {
                database.weatherDao().deleteAll()
                settings.setLastUpdate(0L)
            }
        }
    }

    override fun getProviders(): Flow<List<WeatherProviderInfo>> {
        val providers = mutableListOf<WeatherProviderInfo>()
        providers.add(WeatherProviderInfo(BrightSkyProvider.Id, context.getString(R.string.provider_brightsky)))
        if (OpenWeatherMapProvider.isAvailable(context)) {
            providers.add(WeatherProviderInfo(OpenWeatherMapProvider.Id, context.getString(R.string.provider_openweathermap)))
        }
        if (MetNoProvider.isAvailable(context)) {
            providers.add(WeatherProviderInfo(MetNoProvider.Id, context.getString(R.string.provider_metno)))
        }
        if (HereProvider.isAvailable(context)) {
            providers.add(WeatherProviderInfo(HereProvider.Id, context.getString(R.string.provider_here)))
        }
        val pluginProviders = pluginRepository.findMany(type = PluginType.Weather, enabled = true)
        return pluginProviders.map {
            providers + it.map {
                WeatherProviderInfo(it.authority, it.label)
            }
        }
    }
}

class WeatherUpdateWorker(val context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params), KoinComponent {

    private val appDatabase: AppDatabase by inject()
    private val settings: WeatherSettings by inject()

    override suspend fun doWork(): Result {
        Log.d("WeatherUpdateWorker", "Requesting weather data")
        val settingsData = settings.first()
        val provider = WeatherProvider.getInstance(settingsData.provider)

        val updateInterval = provider.getUpdateInterval()
        val lastUpdate = settingsData.lastUpdate

        if (lastUpdate + updateInterval > System.currentTimeMillis()) {
            Log.d("MM20", "No weather update required")
            return Result.failure()
        }

        val weatherData = if (settingsData.autoLocation) {
            val latLon = getLastKnownLocation() ?: settingsData.lastLocation
            if (latLon == null) {
                Log.e("WeatherUpdateWorker", "Could not get location")
                return Result.failure()
            }
            settings.setLastLocation(latLon)
            provider.getWeatherData(latLon.lat, latLon.lon)
        } else {
            val location = settings.location.first()
            if (location == null) {
                Log.e("WeatherUpdateWorker", "Location not set")
                return Result.failure()
            }
            provider.getWeatherData(location)
        }

        return if (weatherData == null) {
            Log.w("WeatherUpdateWorker", "Weather update failed")
            Result.retry()
        } else {
            Log.i("WeatherUpdateWorker", "Weather update succeeded")
            appDatabase.weatherDao()
                .replaceAll(weatherData.map { it.toDatabaseEntity() })
            settings.setLastUpdate(System.currentTimeMillis())
            Result.success()
        }
    }

    private fun getLastKnownLocation(): LatLon? {
        val lm = context.getSystemService<LocationManager>()!!
        var location: Location? = null
        if (context.checkPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            location = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        }
        if (location == null && context.checkPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            location = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }
        return location?.let { LatLon(it.latitude, it.longitude) }
    }
}