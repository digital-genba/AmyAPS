package app.aaps.plugins.source

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.shared.impl.extensions.safeGetInstalledPackages
import io.reactivex.rxjava3.disposables.CompositeDisposable
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IntelligoPlugin @Inject constructor(
    resourceHelper: ResourceHelper,
    aapsLogger: AAPSLogger,
    private val sp: SP,
    private val context: Context,
    private val persistenceLayer: PersistenceLayer,
    private val dateUtil: DateUtil,
    private val fabricPrivacy: FabricPrivacy
) : AbstractBgSourcePlugin(
    PluginDescription()
        .mainType(PluginType.BGSOURCE)
        .fragmentClass(BGSourceFragment::class.java.name)
        .pluginIcon(app.aaps.core.ui.R.drawable.ic_intelligo)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .pluginName(R.string.intelligo)
        .shortName(R.string.intelligo)
        .preferencesVisibleInSimpleMode(false)
        .description(R.string.description_source_intelligo),
    aapsLogger, resourceHelper
), BgSource {

    private val handler = Handler(HandlerThread(this::class.java.simpleName + "Handler").also { it.start() }.looper)
    private lateinit var refreshLoop: Runnable

    private val contentUri: Uri = Uri.parse("content://$AUTHORITY/$TABLE_NAME")

    init {
        refreshLoop = Runnable {
            try {
                handleNewData()
            } catch (e: Exception) {
                fabricPrivacy.logException(e)
                aapsLogger.error("Error while processing data", e)
            }
            val lastReadTimestamp = sp.getLong(R.string.key_last_processed_intelligo_timestamp, 0L)
            val differenceToNow = INTERVAL - (dateUtil.now() - lastReadTimestamp) % INTERVAL + T.secs(10).msecs()
            handler.postDelayed(refreshLoop, differenceToNow)
        }
    }

    private val disposable = CompositeDisposable()

    override fun onStart() {
        super.onStart()
        handler.postDelayed(refreshLoop, T.secs(30).msecs()) // do not start immediately, app may be still starting
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(refreshLoop)
        disposable.clear()
    }

    @SuppressLint("CheckResult")
    private fun handleNewData() {
        if (!isEnabled()) return

        for (pack in context.packageManager.safeGetInstalledPackages(PackageManager.GET_PROVIDERS)) {
            val providers = pack.providers
            if (providers != null) {
                for (provider in providers) {
                    Log.d("Example", "provider: " + provider.authority)
                }
            }
        }

        context.contentResolver.query(contentUri, null, null, null, null)?.let { cr ->
            val glucoseValues = mutableListOf<GV>()
            val calibrations = mutableListOf<PersistenceLayer.Calibration>()
            cr.moveToFirst()

            while (!cr.isAfterLast) {
                val timestamp = cr.getLong(0)
                val value = cr.getDouble(1) //value in mmol/l...
                val curr = cr.getDouble(2)

                // bypass already processed
                if (timestamp < sp.getLong(R.string.key_last_processed_intelligo_timestamp, 0L)) {
                    cr.moveToNext()
                    continue
                }

                if (timestamp > dateUtil.now() || timestamp == 0L) {
                    aapsLogger.error(LTag.BGSOURCE, "Error in received data date/time $timestamp")
                    cr.moveToNext()
                    continue
                }

                if (value < 2 || value > 25) {
                    aapsLogger.error(LTag.BGSOURCE, "Error in received data value (value out of bounds) $value")
                    cr.moveToNext()
                    continue
                }

                if (curr != 0.0)
                    glucoseValues += GV(
                        timestamp = timestamp,
                        value = value * Constants.MMOLL_TO_MGDL,
                        raw = 0.0,
                        noise = null,
                        trendArrow = TrendArrow.NONE,
                        sourceSensor = SourceSensor.INTELLIGO_NATIVE
                    )
                else
                    calibrations.add(
                        PersistenceLayer.Calibration(
                            timestamp = timestamp,
                            value = value,
                            glucoseUnit = GlucoseUnit.MMOL
                        )
                    )
                sp.putLong(R.string.key_last_processed_intelligo_timestamp, timestamp)
                cr.moveToNext()
            }
            cr.close()

            if (glucoseValues.isNotEmpty() || calibrations.isNotEmpty())
                persistenceLayer.insertCgmSourceData(Sources.Intelligo, glucoseValues, calibrations, null)
                    .blockingGet()
        }
    }

    companion object {

        const val AUTHORITY = "alexpr.co.uk.infinivocgm.intelligo.cgm_db.CgmExternalProvider"
        const val TABLE_NAME = "CgmReading"
        const val INTERVAL = 180000L // 3 min
    }
}
