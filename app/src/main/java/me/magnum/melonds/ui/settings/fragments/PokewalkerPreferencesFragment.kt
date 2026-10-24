package me.magnum.melonds.ui.settings.fragments

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.magnum.melonds.R
import me.magnum.melonds.pokewalker.CreditResult
import me.magnum.melonds.pokewalker.HealthConnectSteps
import me.magnum.melonds.pokewalker.LocalDays
import me.magnum.melonds.pokewalker.Pokewalker
import me.magnum.melonds.pokewalker.PokewalkerDeviceActivity
import me.magnum.melonds.pokewalker.PokewalkerNative
import me.magnum.melonds.pokewalker.PokewalkerWidget
import me.magnum.melonds.pokewalker.StepFileImporter
import me.magnum.melonds.pokewalker.StepLedger
import me.magnum.melonds.pokewalker.WalkerStatus
import me.magnum.melonds.ui.settings.PreferenceFragmentTitleProvider
import org.json.JSONObject
import java.text.NumberFormat
import kotlin.math.abs
import kotlin.random.Random

class PokewalkerPreferencesFragment : BasePreferenceFragment(), PreferenceFragmentTitleProvider {

    private val numbers: NumberFormat = NumberFormat.getIntegerInstance()

    private val healthPermissionLauncher: ActivityResultLauncher<Set<String>>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            registerForActivityResult(HealthConnectSteps.permissionContract()) { granted ->
                if (granted.containsAll(HealthConnectSteps.PERMISSIONS)) {
                    syncFromHealthConnect()
                    context?.let { if (Pokewalker.autoSyncEnabled(it)) requestBackgroundAccess() }
                } else {
                    toast(getString(R.string.pokewalker_health_permission_denied))
                }
            }
        } else {
            null
        }

    // Health Connect's "read in the background" access, for the hourly step sync.
    private val backgroundPermissionLauncher: ActivityResultLauncher<Set<String>>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            registerForActivityResult(HealthConnectSteps.permissionContract()) { granted ->
                if (!granted.contains(HealthConnectSteps.READ_IN_BACKGROUND)) {
                    toast(getString(R.string.pokewalker_auto_sync_no_background))
                }
            }
        } else {
            null
        }

    private val stepsFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importStepsFile(it) }
    }
    private val eepromImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importEeprom(it) }
    }
    private val eepromExportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { exportEeprom(it) }
    }
    private val logExportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { exportLog(it) }
    }

    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Pokewalker.KEY_IR_MODE || key == Pokewalker.KEY_TCP_PORT || key == Pokewalker.KEY_LOG_PACKETS) {
            context?.let { Pokewalker.applySettings(it.applicationContext) }
            updateConnectionPreferences()
            refreshStatus()
        }
        if (key == Pokewalker.KEY_AUTO_SYNC && context?.let { Pokewalker.autoSyncEnabled(it) } == true) {
            requestBackgroundAccess()
        }
        if (key == Pokewalker.KEY_STEP_GOAL) {
            updateGoalSummary()
            context?.let { PokewalkerWidget.updateAll(it.applicationContext) }
        }
    }

    private fun updateGoalSummary() {
        val ctx = context ?: return
        findPreference<EditTextPreference>(Pokewalker.KEY_STEP_GOAL)?.summary =
            getString(R.string.pokewalker_step_goal_summary, numbers.format(Pokewalker.stepGoal(ctx)))
    }

    /** Asks Health Connect for background reading when it isn't granted yet (only if Health Connect is set up). */
    private fun requestBackgroundAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val healthConnect = HealthConnectSteps(requireContext().applicationContext)
        if (healthConnect.availability() != HealthConnectSteps.Availability.AVAILABLE) return
        lifecycleScope.launch {
            val hasSteps = runCatching { healthConnect.hasPermission() }.getOrDefault(false)
            val hasBackground = runCatching { healthConnect.hasBackgroundPermission() }.getOrDefault(false)
            if (hasSteps && !hasBackground) {
                launchSafely { backgroundPermissionLauncher?.launch(HealthConnectSteps.BACKGROUND_PERMISSIONS) }
            }
        }
    }

    override fun getTitle() = getString(R.string.pokewalker)

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_pokewalker, rootKey)
        findPreference<EditTextPreference>(Pokewalker.KEY_STEP_GOAL)?.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_NUMBER
        }
        updateGoalSummary()

        findPreference<Preference>("pokewalker_status")?.setOnPreferenceClickListener {
            refreshStatus()
            true
        }
        findPreference<Preference>("pokewalker_device")?.setOnPreferenceClickListener {
            launchSafely { startActivity(Intent(requireContext(), PokewalkerDeviceActivity::class.java)) }
            true
        }
        findPreference<Preference>("pokewalker_radar")?.setOnPreferenceClickListener {
            playRadar()
            true
        }
        findPreference<Preference>("pokewalker_dowsing")?.setOnPreferenceClickListener {
            playDowsing()
            true
        }
        findPreference<Preference>("pokewalker_sync_health_connect")?.setOnPreferenceClickListener {
            startHealthConnectSync()
            true
        }
        findPreference<Preference>("pokewalker_import_steps")?.setOnPreferenceClickListener {
            launchSafely { stepsFileLauncher.launch(arrayOf("application/zip", "text/*", "application/octet-stream", "*/*")) }
            true
        }
        findPreference<EditTextPreference>("pokewalker_add_steps")?.apply {
            setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_NUMBER
                editText.setText("")
            }
            setOnPreferenceChangeListener { _, newValue ->
                addManualSteps(newValue as? String)
                false
            }
        }
        findPreference<Preference>("pokewalker_export_eeprom")?.setOnPreferenceClickListener {
            launchSafely { eepromExportLauncher.launch("pokewalker-eeprom.bin") }
            true
        }
        findPreference<Preference>("pokewalker_import_eeprom")?.setOnPreferenceClickListener {
            confirm(R.string.pokewalker_import_eeprom, R.string.pokewalker_import_eeprom_confirm) {
                launchSafely { eepromImportLauncher.launch(arrayOf("application/octet-stream", "*/*")) }
            }
            true
        }
        findPreference<Preference>("pokewalker_export_log")?.setOnPreferenceClickListener {
            launchSafely { logExportLauncher.launch("pokewalker-log.txt") }
            true
        }
        findPreference<Preference>("pokewalker_unpair")?.setOnPreferenceClickListener {
            confirm(R.string.pokewalker_unpair, R.string.pokewalker_unpair_confirm) { unpairWalker() }
            true
        }
        findPreference<Preference>("pokewalker_reset")?.setOnPreferenceClickListener {
            confirm(R.string.pokewalker_reset, R.string.pokewalker_reset_confirm) { resetWalker() }
            true
        }
        updateConnectionPreferences()
    }

    override fun onResume() {
        super.onResume()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(settingsListener)
        Pokewalker.applySettings(requireContext().applicationContext)
        refreshStatus()
    }

    override fun onPause() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(settingsListener)
        super.onPause()
    }

    // ---- status -------------------------------------------------------------------

    private fun refreshStatus() {
        val statusPreference = findPreference<Preference>("pokewalker_status") ?: return
        lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) { runCatching { Pokewalker.status() }.getOrNull() }
            if (!isAdded) return@launch
            when {
                status == null || !status.open -> {
                    statusPreference.title = getString(R.string.pokewalker_status_unavailable)
                    statusPreference.summary = null
                }
                status.sessionActive -> {
                    statusPreference.title = getString(R.string.pokewalker_status_busy)
                    statusPreference.summary = null
                }
                status.hasPokemon -> {
                    statusPreference.title = getString(R.string.pokewalker_status_walking_title, status.pokemonName, status.level, status.route)
                    statusPreference.summary = walkingSummary(status)
                }
                status.registered -> {
                    val owner = listOf(status.trainer, status.pairedWith).filter { it.isNotBlank() }.joinToString(" · ")
                    statusPreference.title = getString(R.string.pokewalker_status_idle_title, owner)
                    statusPreference.summary = getString(R.string.pokewalker_status_idle)
                }
                else -> {
                    statusPreference.title = getString(R.string.pokewalker_status_unpaired_title)
                    statusPreference.summary = getString(R.string.pokewalker_status_unpaired)
                }
            }
            // what the walker is waiting for, or the last thing it had to say about the game
            val pairingText = Pokewalker.unsavedTransferText(requireContext(), status) ?: status?.notice?.takeIf { it.isNotBlank() }
            if (pairingText != null) {
                val current = statusPreference.summary?.toString()
                statusPreference.summary = if (current.isNullOrBlank()) pairingText else "$current\n\n$pairingText"
            }
            val copyText = Pokewalker.copyCheckText(requireContext(), status?.copyCheck)
            if (copyText != null) {
                val current = statusPreference.summary?.toString()
                statusPreference.summary = if (current.isNullOrBlank()) copyText else "$current\n\n$copyText"
            }
        }
    }

    private fun walkingSummary(status: WalkerStatus): String {
        val main = getString(
            R.string.pokewalker_status_walking,
            numbers.format(status.todaySteps),
            numbers.format(status.totalSteps),
            numbers.format(status.watts.toLong()),
            status.caught,
            status.dowsed + status.gifted,
        )
        val since = if (status.strollStartUnix > 0) {
            "\n" + getString(R.string.pokewalker_status_since, LocalDays.format(LocalDays.dayOf(status.strollStartUnix * 1000)))
        } else ""
        return main + since
    }

    private fun updateConnectionPreferences() {
        val mode = preferenceManager.sharedPreferences?.getString(Pokewalker.KEY_IR_MODE, Pokewalker.MODE_VIRTUAL)
        findPreference<EditTextPreference>(Pokewalker.KEY_TCP_PORT)?.apply {
            isVisible = mode == Pokewalker.MODE_TCP
            summary = getString(R.string.pokewalker_tcp_port_summary, text ?: "8081")
        }
    }

    // ---- steps ----------------------------------------------------------------------

    private fun startHealthConnectSync() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            toast(getString(R.string.pokewalker_health_unsupported))
            return
        }
        val healthConnect = HealthConnectSteps(requireContext().applicationContext)
        when (healthConnect.availability()) {
            HealthConnectSteps.Availability.UNSUPPORTED -> toast(getString(R.string.pokewalker_health_unsupported))
            HealthConnectSteps.Availability.NEEDS_INSTALL -> {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.pokewalker_health_install_title)
                    .setMessage(R.string.pokewalker_health_install)
                    .setPositiveButton(R.string.pokewalker_health_install_action) { _, _ ->
                        try {
                            startActivity(HealthConnectSteps.installIntent())
                        } catch (e: ActivityNotFoundException) {
                            toast(getString(R.string.pokewalker_health_unsupported))
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            HealthConnectSteps.Availability.AVAILABLE -> lifecycleScope.launch {
                val granted = runCatching { healthConnect.hasPermission() }.getOrDefault(false)
                if (granted) {
                    syncFromHealthConnect()
                } else {
                    launchSafely { healthPermissionLauncher?.launch(HealthConnectSteps.PERMISSIONS) }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun syncFromHealthConnect() {
        val appContext = requireContext().applicationContext
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val status = Pokewalker.status()
                    if (!status.hasPokemon || status.strollStartUnix <= 0) {
                        CreditResult.NoStroll
                    } else {
                        val now = System.currentTimeMillis()
                        val from = maxOf(status.strollStartUnix * 1000, now - 30 * LocalDays.DAY_MS)
                        val daily = HealthConnectSteps(appContext).stepsByDay(from, now)
                        StepLedger(appContext).credit(daily)
                    }
                }
            }
            showCreditResult(result)
        }
    }

    private fun importStepsFile(uri: Uri) {
        val appContext = requireContext().applicationContext
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val daily = StepFileImporter.read(appContext, uri)
                    if (daily.isEmpty()) CreditResult.Error(appContext.getString(R.string.pokewalker_import_steps_empty))
                    else StepLedger(appContext).credit(daily)
                }
            }
            showCreditResult(result)
        }
    }

    private fun addManualSteps(value: String?) {
        val steps = value?.trim()?.toIntOrNull()
        if (steps == null || steps <= 0) return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val status = Pokewalker.status()
                    when {
                        status.sessionActive -> CreditResult.Busy
                        !status.hasPokemon -> CreditResult.NoStroll
                        else -> PokewalkerNative.addSteps(steps, 0)?.let { CreditResult.Error(it) }
                            ?: CreditResult.Added(steps.toLong(), Pokewalker.status().watts)
                    }
                }
            }
            showCreditResult(result)
        }
    }

    private fun showCreditResult(result: Result<CreditResult>) {
        if (!isAdded) return
        val credit = result.getOrElse { error ->
            toast(getString(R.string.pokewalker_error, error.message ?: error.javaClass.simpleName))
            return
        }
        when (credit) {
            is CreditResult.Added -> toast(
                if (credit.steps == 0L) getString(R.string.pokewalker_steps_up_to_date)
                else getString(R.string.pokewalker_steps_added, numbers.format(credit.steps), numbers.format(credit.wattsNow.toLong()))
            )
            CreditResult.NoStroll -> AlertDialog.Builder(requireContext())
                .setTitle(R.string.pokewalker_no_stroll_title)
                .setMessage(R.string.pokewalker_no_stroll)
                .setPositiveButton(R.string.ok, null)
                .show()
            CreditResult.Busy -> toast(getString(R.string.pokewalker_status_busy))
            is CreditResult.Error -> toast(getString(R.string.pokewalker_error, credit.message))
        }
        refreshStatus()
    }

    // ---- Poké Radar and dowsing -----------------------------------------------------

    private fun playRadar() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { JSONObject(PokewalkerNative.radarEncounter()) }.getOrNull() }
            if (!isAdded) return@launch
            refreshStatus()
            if (result == null) {
                toast(getString(R.string.pokewalker_error, "?"))
                return@launch
            }
            val error = result.optString("error")
            if (error.isNotEmpty()) {
                toast(getString(R.string.pokewalker_error, error))
                return@launch
            }
            val slot = result.optInt("slot")
            val name = result.optString("speciesName").ifBlank { "?" }
            val level = result.optInt("level")
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.pokewalker_radar)
                .setMessage(getString(R.string.pokewalker_radar_appeared, name, level))
                .setPositiveButton(R.string.pokewalker_radar_throw) { _, _ -> throwBall(slot, name) }
                .setNegativeButton(R.string.pokewalker_radar_run) { _, _ -> toast(getString(R.string.pokewalker_radar_ran, name)) }
                .setCancelable(false)
                .show()
        }
    }

    private fun throwBall(slot: Int, name: String) {
        // Rarer route Pokémon (lower slots) are harder to catch, as on the Pokéwalker.
        val chance = when (slot) {
            0 -> 0.5
            1 -> 0.6
            else -> 0.75
        }
        if (Random.nextDouble() >= chance) {
            toast(getString(R.string.pokewalker_radar_fled, name))
            return
        }
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) { runCatching { PokewalkerNative.storeCaught(slot) }.getOrElse { it.message ?: "?" } }
            if (!isAdded) return@launch
            toast(if (error == null) getString(R.string.pokewalker_radar_caught, name) else getString(R.string.pokewalker_error, error))
            refreshStatus()
        }
    }

    private fun playDowsing() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { JSONObject(PokewalkerNative.dowsingStart()) }.getOrNull() }
            if (!isAdded) return@launch
            refreshStatus()
            if (result == null) {
                toast(getString(R.string.pokewalker_error, "?"))
                return@launch
            }
            val error = result.optString("error")
            if (error.isNotEmpty()) {
                toast(getString(R.string.pokewalker_error, error))
                return@launch
            }
            val item = result.optInt("item")
            val itemName = result.optString("itemName").ifBlank { getString(R.string.pokewalker_item_unknown) }
            showGrass(item, itemName, hidden = Random.nextInt(6), triesLeft = 2, title = null)
        }
    }

    private fun showGrass(item: Int, itemName: String, hidden: Int, triesLeft: Int, title: String?) {
        val patches = Array<CharSequence>(6) { getString(R.string.pokewalker_dowsing_bush, it + 1) }
        AlertDialog.Builder(requireContext())
            .setTitle(title ?: getString(R.string.pokewalker_dowsing_title, triesLeft))
            .setItems(patches) { _, which ->
                when {
                    which == hidden -> claimItem(item, itemName)
                    triesLeft > 1 -> {
                        val hint = if (abs(which - hidden) == 1) R.string.pokewalker_dowsing_close else R.string.pokewalker_dowsing_far
                        showGrass(item, itemName, hidden, triesLeft - 1, getString(hint, triesLeft - 1))
                    }
                    else -> toast(getString(R.string.pokewalker_dowsing_nothing))
                }
            }
            .setCancelable(false)
            .show()
    }

    private fun claimItem(item: Int, itemName: String) {
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) { runCatching { PokewalkerNative.storeDowsed(item) }.getOrElse { it.message ?: "?" } }
            if (!isAdded) return@launch
            toast(if (error == null) getString(R.string.pokewalker_dowsing_found, itemName) else getString(R.string.pokewalker_error, error))
            refreshStatus()
        }
    }

    // ---- walker data ----------------------------------------------------------------

    private fun importEeprom(uri: Uri) {
        val appContext = requireContext().applicationContext
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = appContext.contentResolver.openInputStream(uri)?.use { input ->
                        val data = input.readBytes()
                        if (data.size > 70_000) null else data
                    }
                    if (bytes == null) appContext.getString(R.string.pokewalker_import_eeprom_invalid) else PokewalkerNative.importImage(bytes)
                }.getOrElse { it.message ?: it.javaClass.simpleName }
            }
            if (!isAdded) return@launch
            toast(if (error == null) getString(R.string.pokewalker_imported) else getString(R.string.pokewalker_error, error))
            refreshStatus()
        }
    }

    private fun exportEeprom(uri: Uri) {
        val appContext = requireContext().applicationContext
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val image = PokewalkerNative.exportImage() ?: return@runCatching false
                    appContext.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        out.write(image)
                        true
                    } ?: false
                }.getOrDefault(false)
            }
            if (isAdded) toast(getString(if (ok) R.string.pokewalker_exported else R.string.pokewalker_export_failed))
        }
    }

    private fun exportLog(uri: Uri) {
        val appContext = requireContext().applicationContext
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val log = Pokewalker.logFile(appContext)
                    appContext.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        if (log.exists()) {
                            log.inputStream().use { it.copyTo(out) }
                        }
                        true
                    } ?: false
                }.getOrDefault(false)
            }
            if (isAdded) toast(getString(if (ok) R.string.pokewalker_exported else R.string.pokewalker_export_failed))
        }
    }

    private fun unpairWalker() {
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) { runCatching { PokewalkerNative.unpairWalker() }.getOrElse { it.message } }
            if (!isAdded) return@launch
            toast(if (error == null) getString(R.string.pokewalker_unpair_done) else getString(R.string.pokewalker_error, error))
            refreshStatus()
        }
    }

    private fun resetWalker() {
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) { runCatching { PokewalkerNative.resetWalker() }.getOrElse { it.message } }
            if (!isAdded) return@launch
            toast(if (error == null) getString(R.string.pokewalker_reset_done) else getString(R.string.pokewalker_error, error))
            refreshStatus()
        }
    }

    // ---- helpers --------------------------------------------------------------------

    private fun confirm(titleRes: Int, messageRes: Int, onConfirm: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setPositiveButton(R.string.ok) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun launchSafely(block: () -> Unit) {
        try {
            block()
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.pokewalker_no_file_picker))
        }
    }

    private fun toast(message: String) {
        val context: Context = context ?: return
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}
