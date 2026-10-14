package com.lizongying.mytv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lizongying.mytv.api.YSP
import com.lizongying.mytv.models.TVListViewModel
import com.lizongying.mytv.models.TVViewModel
import com.lizongying.mytv.models.ProgramType
import com.lizongying.mytv.playlist.ChannelIdentity
import com.lizongying.mytv.playlist.SourceQualityResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Main screen with a two-column layout: group menu on the left, channel list
 * in the middle, playback on the right.
 */
class MainFragment : Fragment() {

    private lateinit var groupList: RecyclerView
    private lateinit var channelList: RecyclerView

    private lateinit var groupAdapter: GroupAdapter
    private lateinit var channelAdapter: ChannelAdapter

    private val tvListViewModel: TVListViewModel by lazy {
        ViewModelProvider(requireActivity())[TVListViewModel::class.java]
    }

    private var groups: List<String> = emptyList()
    private var channels: List<TVViewModel> = emptyList()

    /** Currently selected group index. */
    private var currentGroupIndex = 0

    /** Currently selected channel index within the group. */
    private var currentChannelIndex = 0

    private var itemPosition = 0
    private val playbackHandler = Handler(Looper.getMainLooper())
    private var pendingChannel: TVViewModel? = null
    private var handlingFailure = false
    private var dataRefreshJob: Job? = null
    private var lastSubscriptionRefresh = 0L
    private var lastEpgRefresh = 0L
    private var observedChannels: List<TVViewModel> = emptyList()
    private var menuGeneration = 0
    private var restoringMenu = false
    private var deferredActiveTV: TV? = null
    private var sourceOrder: List<Int> = emptyList()
    private val sourceFailures = com.lizongying.mytv.playlist.SourceFailures()
    private val playbackTimeout = Runnable {
        pendingChannel?.let { onPlaybackFailed(it, "播放加载超时") }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.main_browse, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        groupList = view.findViewById(R.id.group_list)
        channelList = view.findViewById(R.id.channel_list)

        groupList.layoutManager = LinearLayoutManager(context)
        channelList.layoutManager = LinearLayoutManager(context)

        groupAdapter = GroupAdapter { position -> onGroupSelected(position) }
        channelAdapter = ChannelAdapter { position -> onChannelSelected(position) }

        groupList.adapter = groupAdapter
        channelList.adapter = channelAdapter
        if ((activity as? MainActivity)?.isTelevision == false) {
            groupList.layoutParams = groupList.layoutParams.apply { width = dp(150) }
            channelList.layoutParams = channelList.layoutParams.apply { width = dp(220) }
        }

        // Start with focus on group list
        groupList.post {
            groupList.requestFocus()
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        activity?.let { YSP.init(it) }

        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { TVList.load(requireContext().applicationContext) }
            }
            loaded.onFailure { error ->
                Log.e(TAG, "playlist load failed", error)
                (activity as? MainActivity)?.onRequestFinished(
                    error.message ?: "频道列表加载失败"
                )
                return@launch
            }

            // Build TVViewModels from loaded TV objects
            tvListViewModel.clearChannels()
            TVList.list.values.flatten().distinctBy { it.id }.forEach { tv ->
                val tvViewModel = TVViewModel(tv)
                tvListViewModel.addTVViewModel(tvViewModel)
            }

            Log.i(TAG, "TVListViewModel populated with ${tvListViewModel.size()} channels")

            loadRows()
            observeChannels()

            // Trigger initial playback
            val savedKey = requireContext().getSharedPreferences("browse_state", 0)
                .getString("channel", null)
            val lastPosition = tvListViewModel.tvListViewModel.value.orEmpty()
                .indexOfFirst { ChannelIdentity.key(it.getTV().title) == savedKey }
                .takeIf { it >= 0 } ?: SP.itemPosition
            if (lastPosition >= 0 && lastPosition < tvListViewModel.size()) {
                selectPlayback(lastPosition)
            } else if (tvListViewModel.size() > 0) {
                selectPlayback(0)
            }

            (activity as? MainActivity)?.fragmentReady("MainFragment")

            locateCurrentChannel()
            lastSubscriptionRefresh = if (TVList.loadedFromCache) -30 * 60_000L else SystemClock.elapsedRealtime()
            startDataRefresh()
        }
    }

    private fun loadRows() {
        groups = TVList.list.keys.toList()
        groupAdapter.submitList(groups)

        if (groups.isNotEmpty()) {
            selectGroup(0)
        }
    }

    private fun observeChannels() {
        observedChannels.forEach {
            it.ready.removeObservers(viewLifecycleOwner)
            it.change.removeObservers(viewLifecycleOwner)
            it.errInfo.removeObservers(viewLifecycleOwner)
        }
        observedChannels = tvListViewModel.tvListViewModel.value.orEmpty().toList()
        tvListViewModel.tvListViewModel.value?.forEach { tvViewModel ->
            tvViewModel.ready.observe(viewLifecycleOwner) {
                if (it == true && pendingChannel === tvViewModel) {
                    (activity as? MainActivity)?.play(tvViewModel)
                }
            }

            tvViewModel.change.observe(viewLifecycleOwner) {
                if (it == true && pendingChannel === tvViewModel) {
                    val title = tvViewModel.getTV().title
                    Log.i(TAG, "switch $title")
                    val pid = tvViewModel.getTV().pid
                    if (pid != "") {
                        lifecycleScope.launch(Dispatchers.IO) {
                            tvViewModel.let { Request.fetchData(it) }
                        }
                        (activity as? MainActivity)?.showInfoFragment(tvViewModel)
                    } else {
                        if (check(tvViewModel)) {
                            (activity as? MainActivity)?.play(tvViewModel)
                            (activity as? MainActivity)?.showInfoFragment(tvViewModel)
                        } else {
                            onPlaybackFailed(tvViewModel, "播放地址无效")
                        }
                    }
                }
            }

            tvViewModel.errInfo.observe(viewLifecycleOwner) { message ->
                if (!message.isNullOrBlank() && pendingChannel === tvViewModel) {
                    onPlaybackFailed(tvViewModel, message)
                }
            }
        }
    }

    private fun onGroupSelected(position: Int) {
        Log.i(TAG, "onGroupSelected: $position")
        if (restoringMenu) return
        val generation = menuGeneration
        // Ignore queued focus events from a menu that has since been restored.
        groupList.post {
            if (view != null && generation == menuGeneration && position != currentGroupIndex) {
                selectGroup(position)
            }
        }
    }

    private fun onChannelSelected(position: Int) {
        Log.i(TAG, "onChannelSelected: $position")
        currentChannelIndex = position
        if (position in channels.indices) {
            val tvViewModel = channels[position]
            selectPlayback(tvViewModel.getTV().id)
            val mainActivity = activity as? MainActivity
            if (mainActivity?.isTelevision == true || channelList.isInTouchMode == false) {
                mainActivity?.hideMainFragment()
            }
        }
    }

    private fun selectGroup(position: Int) {
        if (position !in groups.indices) return
        Log.i(TAG, "selectGroup: $position, total groups: ${groups.size}")
        currentGroupIndex = position
        currentChannelIndex = 0

        if (position in groups.indices) {
            val groupName = groups[position]
            Log.i(TAG, "selecting group: $groupName")
            channels = TVList.list[groupName]?.mapNotNull { tv ->
                tvListViewModel.tvListViewModel.value?.find { it.getTV().id == tv.id }
            } ?: emptyList()

            Log.i(TAG, "channels in group: ${channels.size}")
            channelAdapter.submitList(channels)
            groupAdapter.setSelected(position)

            if (channels.isNotEmpty()) {
                currentChannelIndex = channels.indexOf(tvListViewModel.getTVViewModelCurrent())
                channelAdapter.setSelected(currentChannelIndex)
                channelList.post {
                    channelList.scrollToPosition(currentChannelIndex.coerceAtLeast(0))
                }
            }
        }
    }

    fun locateCurrentChannel(requestFocus: Boolean = true) {
        if (!::channelList.isInitialized || groups.isEmpty()) return
        val current = tvListViewModel.getTVViewModelCurrent() ?: return
        val groupName = groups.getOrNull(currentGroupIndex)
        val currentId = current.getTV().id
        val target = if (TVList.list[groupName]?.any { it.id == currentId } == true) {
            currentGroupIndex
        } else groups.indexOfFirst { name -> TVList.list[name]?.any { it.id == currentId } == true }
        if (target < 0) return
        menuGeneration++
        restoringMenu = true
        selectGroup(target)
        currentChannelIndex = channels.indexOf(current)
        channelAdapter.setSelected(currentChannelIndex)
        groupList.scrollToPosition(target)
        val position = currentChannelIndex
        channelList.scrollToPosition(position.coerceAtLeast(0))
        channelList.post {
            if (requestFocus && !isHidden && position >= 0) {
                channelList.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
                    ?: channelList.requestFocus()
            }
            restoringMenu = false
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) locateCurrentChannel()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun handleKeyEvent(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (currentGroupIndex > 0) {
                    onGroupSelected(currentGroupIndex - 1)
                    groupList.scrollToPosition(currentGroupIndex)
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (currentGroupIndex < groups.size - 1) {
                    onGroupSelected(currentGroupIndex + 1)
                    groupList.scrollToPosition(currentGroupIndex)
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (currentChannelIndex < channels.size) {
                    onChannelSelected(currentChannelIndex)
                }
                true
            }

            else -> false
        }
    }

    fun play(itemPosition: Int) {
        selectPlayback(itemPosition)
    }

    fun prev() {
        if (itemPosition > 0) {
            selectPlayback(itemPosition - 1)
        }
    }

    fun next() {
        if (itemPosition < tvListViewModel.size() - 1) {
            selectPlayback(itemPosition + 1)
        }
    }

    fun selectSource(channelId: Int, source: Int) {
        val channel = tvListViewModel.getTVViewModelCurrent() ?: return
        if (channel.getTV().id != channelId) {
            Toast.makeText(context, "频道已切换，请重新选择直播源", Toast.LENGTH_SHORT).show()
            return
        }
        if (source !in channel.videoUrl.value.orEmpty().indices) return
        sourceFailures.clear()
        channel.setVideoIndex(source)
        Log.i(TAG, "manual source selection: ${channel.getTV().title}, source=$source")
        selectPlayback(itemPosition, automatic = true)
    }

    private fun selectPlayback(position: Int, automatic: Boolean = false) {
        if (position !in 0 until tvListViewModel.size()) return
        val channel = tvListViewModel.getTVViewModel(position) ?: return
        val previous = tvListViewModel.getTVViewModelCurrent()
        if (previous !== channel) {
            deferredActiveTV?.let { previous?.updateTV(it) }
            deferredActiveTV = null
        }
        val newChannel = previous !== channel || !automatic
        if (newChannel) {
            sourceFailures.clear()
        }
        itemPosition = position
        tvListViewModel.setItemPosition(position)
        pendingChannel = channel
        handlingFailure = false
        playbackHandler.removeCallbacksAndMessages(null)
        channels.indexOf(channel).takeIf { it >= 0 }?.let {
            currentChannelIndex = it
            channelAdapter.setSelected(it)
        }
        if (newChannel) {
            val urls = channel.videoUrl.value.orEmpty()
            val cached = SourceQualityResolver.preferYangshipin(
                SourceQualityResolver.cachedRanking(requireContext(), urls), channel.getTV().sourceNames)
            sourceOrder = cached.order
            val preferred = cached.order.firstOrNull { !cached.qualities[it].failed }
            if (preferred == null) {
                pendingChannel = null
                (activity as? MainActivity)?.stopPlayback()
                Toast.makeText(context, "该频道来源暂时不可用，请稍后重试或手动选源", Toast.LENGTH_LONG).show()
                return
            }
            channel.setVideoIndex(preferred)
        }
        // Read historical playback results only; never probe an unselected source.
        playbackHandler.postDelayed(playbackTimeout, PLAYBACK_TIMEOUT_MS)
        channel.changed()
    }

    fun onPlaybackFailed(channel: TVViewModel, reason: String) {
        if (handlingFailure) return
        handlingFailure = true
        val failedSource = channel.videoIndex.value
        // Defer the switch so empty URLs cannot recurse through LiveData observers.
        playbackHandler.post {
            if (!isResumed || tvListViewModel.getTVViewModelCurrent() !== channel ||
                channel.videoIndex.value != failedSource) {
                return@post
            }
            Log.w(TAG, "source failed: ${channel.getTV().title}, source=${channel.videoIndex.value}, $reason")
            channel.videoIndex.value?.let {
                sourceFailures.playbackFailed(it)
                channel.videoUrl.value?.getOrNull(it)?.let { url ->
                    SourceQualityResolver.markFailed(requireContext(), url, reason)
                }
            }
            playbackHandler.removeCallbacks(playbackTimeout)
            pendingChannel = null
            (activity as? MainActivity)?.stopPlayback()
            val urls = channel.videoUrl.value.orEmpty()
            val eligible = sourceOrder.filter { index ->
                urls.getOrNull(index)?.let { !SourceQualityResolver.isCoolingDown(requireContext(), it) } == true
            }
            val nextSource = sourceFailures.nextPlayback(eligible, urls.size, eligible.toSet())
            if (nextSource != null) {
                val waitMs = sourceFailures.retryDelayMs()
                Log.i(TAG, "retry same channel source=$nextSource after ${waitMs}ms")
                playbackHandler.postDelayed({
                    if (isResumed && tvListViewModel.getTVViewModelCurrent() === channel &&
                        channel.videoIndex.value == failedSource) {
                        channel.setVideoIndex(nextSource)
                        selectPlayback(itemPosition, automatic = true)
                    }
                }, waitMs)
            } else {
                Toast.makeText(context, "当前频道播放失败，已停止自动重试，请稍后重试或手动选源", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun onPlaybackStarted(channel: TVViewModel) {
        if (tvListViewModel.getTVViewModelCurrent() !== channel) return
        pendingChannel = null
        playbackHandler.removeCallbacks(playbackTimeout)
        Log.i(TAG, "playing ${channel.getTV().title}, source=${channel.videoIndex.value}")
        // Reset the retry budget only after sustained playback, not a transient READY event.
        playbackHandler.postDelayed({
            if (!handlingFailure && pendingChannel == null &&
                tvListViewModel.getTVViewModelCurrent() === channel) sourceFailures.clear()
        }, 60_000)
    }

    private fun startDataRefresh() {
        if (dataRefreshJob?.isActive == true || tvListViewModel.size() == 0 || !isResumed) return
        val context = requireContext().applicationContext
        dataRefreshJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastSubscriptionRefresh >= 30 * 60_000L) {
                    val result = withContext(Dispatchers.IO) { runCatching { TVList.fetch(context) } }
                    result.onSuccess {
                        applySubscription(it)
                        lastSubscriptionRefresh = SystemClock.elapsedRealtime()
                    }.onFailure {
                        lastSubscriptionRefresh = SystemClock.elapsedRealtime() - 25 * 60_000L
                        Log.w(TAG, "subscription refresh failed; keeping existing channels", it)
                    }
                }
                if (lastEpgRefresh == 0L || now - lastEpgRefresh >= 15 * 60_000L) {
                    val success = withContext(Dispatchers.IO) { runCatching { TVList.loadEPG(context) }.getOrDefault(false) }
                    tvListViewModel.tvListViewModel.value.orEmpty().forEach { updateEPG(it) }
                    lastEpgRefresh = SystemClock.elapsedRealtime() - if (success) 0L else 10 * 60_000L
                    Log.i(TAG, "EPG refresh complete, success=$success")
                }
                delay(60_000)
            }
        }
    }

    /** Reuse live view models and remap source indices by URL, keeping the current picture. */
    private fun applySubscription(snapshot: TVList.Snapshot) {
        val current = tvListViewModel.getTVViewModelCurrent()
        val old = tvListViewModel.tvListViewModel.value.orEmpty().associateBy { ChannelIdentity.key(it.getTV().title) }
        val groupName = groups.getOrNull(currentGroupIndex)
        val playingUrl = current?.videoUrl?.value?.getOrNull(current.videoIndex.value ?: 0)
        val previousPending = pendingChannel
        pendingChannel = null
        sourceFailures.clear()
        val rebuilt = snapshot.groups.values.flatten().distinctBy { it.id }.map { tv ->
            val existing = old[ChannelIdentity.key(tv.title)]
            if (existing != null && existing === current && playingUrl != null && playingUrl !in tv.videoUrl) {
                deferredActiveTV = tv
                val retained = tv.copy(videoUrl = tv.videoUrl + playingUrl,
                    sourceNames = tv.sourceNames + "当前播放（订阅已移除）")
                existing.updateTV(retained)
            } else {
                if (existing === current) deferredActiveTV = null
                existing?.updateTV(tv)
            }
            existing ?: TVViewModel(tv)
        }.toMutableList()
        val updatedGroups = snapshot.groups.mapValues { (_, entries) -> entries.toMutableList() }.toMutableMap()
        // A channel removed by the provider can finish playing; next refresh removes it after switching away.
        if (current != null && current !in rebuilt) {
            current.getTV().id = rebuilt.size
            rebuilt += current
            updatedGroups.getOrPut(groupName ?: "当前播放") { mutableListOf() }.add(current.getTV())
        }
        val position = rebuilt.indexOf(current).coerceAtLeast(0)
        TVList.apply(snapshot.copy(groups = updatedGroups))
        tvListViewModel.replaceChannels(rebuilt, position)
        itemPosition = position
        (activity as? MainActivity)?.syncSourceIndex(current)
        sourceOrder = current?.let {
            SourceQualityResolver.preferYangshipin(
                SourceQualityResolver.cachedRanking(requireContext(), it.videoUrl.value.orEmpty()),
                it.getTV().sourceNames).order
        }.orEmpty()
        groups = TVList.list.keys.toList()
        groupAdapter.submitList(groups)
        currentGroupIndex = groups.indexOf(groupName).coerceAtLeast(0)
        observeChannels()
        pendingChannel = previousPending?.takeIf { it in rebuilt }
        locateCurrentChannel(requestFocus = channelList.hasFocus())
        rebuilt.forEach { updateEPG(it) }
        Log.i(TAG, "subscription refreshed without restarting playback: ${rebuilt.size} channels")
    }

    fun fragmentReady() {
        // This is now called from MainActivity but actual initialization
        // happens in onActivityCreated to avoid detach issues
    }

    private fun check(tvViewModel: TVViewModel): Boolean {
        val title = tvViewModel.getTV().title
        val videoUrl = tvViewModel.videoIndex.value?.let { tvViewModel.videoUrl.value?.getOrNull(it) }
        if (videoUrl == null || videoUrl == "") {
            Log.e(TAG, "$title videoUrl is empty")
            return false
        }
        return true
    }

    private fun updateEPG(tvViewModel: TVViewModel) {
        when (tvViewModel.getTV().programType) {
            com.lizongying.mytv.models.ProgramType.Y_PROTO -> {
                Request.fetchYProtoEPG(tvViewModel)
            }

            com.lizongying.mytv.models.ProgramType.Y_JCE -> {
                Request.fetchYJceEPG(tvViewModel)
            }

            com.lizongying.mytv.models.ProgramType.F -> {
                Request.fetchFEPG(tvViewModel)
            }

            com.lizongying.mytv.models.ProgramType.M3U -> {
                tvViewModel.setEPG(TVList.epgFor(tvViewModel.getTV()))
            }
        }
    }

    override fun onResume() {
        Log.i(TAG, "onResume")
        super.onResume()
        if (tvListViewModel.size() > 0) {
            startDataRefresh()
        }
        if (pendingChannel != null) {
            playbackHandler.removeCallbacks(playbackTimeout)
            playbackHandler.postDelayed(playbackTimeout, PLAYBACK_TIMEOUT_MS)
        }
    }

    override fun onStop() {
        Log.i(TAG, "onStop")
        super.onStop()
        dataRefreshJob?.cancel()
        dataRefreshJob = null
        playbackHandler.removeCallbacksAndMessages(null)
        SP.itemPosition = itemPosition
        tvListViewModel.getTVViewModelCurrent()?.let {
            requireContext().getSharedPreferences("browse_state", 0).edit()
                .putString("channel", ChannelIdentity.key(it.getTV().title)).apply()
        }
        Log.i(TAG, "$POSITION $itemPosition saved")
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainFragment"
        private const val POSITION = "position"
        private const val PLAYBACK_TIMEOUT_MS = 20_000L
    }
}
