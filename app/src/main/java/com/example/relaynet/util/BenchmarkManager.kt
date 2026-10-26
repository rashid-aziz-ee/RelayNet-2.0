package com.example.relaynet.util

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

data class BenchmarkConfig(
    val totalPackets: Int = 30,
    val intervalMs: Long = 500L,
    val targetPeerId: String = "" // Empty = Broadcast across all mesh nodes
)

data class BenchmarkSample(
    val sessionId: String,
    val seqNo: Int,
    val sendTimestamp: Long,
    val echoTimestamp: Long,
    val rttMs: Long,
    val latencyMs: Long,
    val hops: Int,
    val senderId: String,
    val receiverId: String
)

data class BenchmarkSummary(
    val sessionId: String,
    val totalSent: Int,
    val totalReceived: Int,
    val packetDeliveryRatio: Float,
    val minLatencyMs: Long,
    val maxLatencyMs: Long,
    val avgLatencyMs: Float,
    val csvFilePath: String
)

class BenchmarkManager(
    private val context: Context,
    private val myDeviceId: String,
    private val sendRawPacketLambda: (String) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _liveProgress = MutableStateFlow("")
    val liveProgress: StateFlow<String> = _liveProgress.asStateFlow()

    private val _lastSummary = MutableStateFlow<BenchmarkSummary?>(null)
    val lastSummary: StateFlow<BenchmarkSummary?> = _lastSummary.asStateFlow()

    private var activeSessionId: String? = null
    private val sentTimestamps = ConcurrentHashMap<Int, Long>()
    private val receivedSamples = mutableListOf<BenchmarkSample>()
    private var testJob: Job? = null

    /**
     * Start automated test run:
     * Transmits N numbered packets at set interval.
     */
    fun startBenchmark(config: BenchmarkConfig) {
        if (_isRunning.value) return

        testJob?.cancel()
        testJob = scope.launch {
            val sessionId = UUID.randomUUID().toString().take(6).uppercase()
            activeSessionId = sessionId
            _isRunning.value = true
            _lastSummary.value = null
            sentTimestamps.clear()
            receivedSamples.clear()

            _liveProgress.value = "Starting Benchmark [$sessionId]: 0/${config.totalPackets} sent..."

            for (seq in 1..config.totalPackets) {
                if (!isActive) break

                val now = System.currentTimeMillis()
                sentTimestamps[seq] = now

                // Packet Format: TEST_PING|sessionId|seqNo|sendTimestamp|ttl|totalPackets|targetId|originDeviceId
                val pingPacket = "TEST_PING|$sessionId|$seq|$now|3|${config.totalPackets}|${config.targetPeerId}|$myDeviceId"
                sendRawPacketLambda(pingPacket)

                val echoCount = receivedSamples.size
                _liveProgress.value = "Running [$sessionId]: Sent $seq/${config.totalPackets} | Echoed: $echoCount"

                delay(config.intervalMs)
            }

            // Grace period: Wait 2.5s for in-flight multi-hop echoes to return
            _liveProgress.value = "Awaiting final in-flight echoes..."
            delay(2500)

            finishBenchmark(sessionId, config.totalPackets)
        }
    }

    fun cancelBenchmark() {
        testJob?.cancel()
        testJob = null
        _isRunning.value = false
        _liveProgress.value = "Benchmark cancelled."
    }

    /**
     * Called when receiving an incoming raw packet over Nearby Connections mesh.
     * Returns true if the packet was a benchmark packet and handled here.
     */
    fun handleIncomingBenchmarkPacket(rawData: String, cameFromEndpoint: String): Boolean {
        val parts = rawData.split("|")
        if (parts.isEmpty()) return false

        when (parts[0]) {
            "TEST_PING" -> {
                // TEST_PING|sessionId|seqNo|sendTimestamp|ttl|totalPackets|targetId|originDeviceId
                if (parts.size < 8) return true
                val sessionId = parts[1]
                val seq = parts[2].toIntOrNull() ?: return true
                val sendTimestamp = parts[3].toLongOrNull() ?: return true
                val ttl = parts[4].toIntOrNull() ?: 1
                val targetId = parts[6]
                val originDeviceId = parts[7]

                // If addressed to specific node and not for me, ignore
                if (targetId.isNotEmpty() && targetId != myDeviceId) return true

                // Calculate hops taken to arrive
                val hops = maxOf(0, 3 - ttl)
                val echoTimestamp = System.currentTimeMillis()

                // Echo back to originator
                // TEST_ECHO|sessionId|seqNo|sendTimestamp|echoTimestamp|hops|receiverId|originDeviceId
                val echoPacket = "TEST_ECHO|$sessionId|$seq|$sendTimestamp|$echoTimestamp|$hops|$myDeviceId|$originDeviceId"
                sendRawPacketLambda(echoPacket)
                return true
            }

            "TEST_ECHO" -> {
                // TEST_ECHO|sessionId|seqNo|sendTimestamp|echoTimestamp|hops|receiverId|originDeviceId
                if (parts.size < 8) return true
                val sessionId = parts[1]
                val seq = parts[2].toIntOrNull() ?: return true
                val sendTimestamp = parts[3].toLongOrNull() ?: return true
                val echoTimestamp = parts[4].toLongOrNull() ?: return true
                val hops = parts[5].toIntOrNull() ?: 0
                val receiverId = parts[6]
                val originDeviceId = parts[7]

                // Only record if this echo is for my current active session
                if (originDeviceId != myDeviceId || sessionId != activeSessionId) return true

                val now = System.currentTimeMillis()
                val rtt = maxOf(1L, now - sendTimestamp)
                val latency = rtt / 2

                synchronized(receivedSamples) {
                    if (receivedSamples.none { it.seqNo == seq }) {
                        val sample = BenchmarkSample(
                            sessionId = sessionId,
                            seqNo = seq,
                            sendTimestamp = sendTimestamp,
                            echoTimestamp = echoTimestamp,
                            rttMs = rtt,
                            latencyMs = latency,
                            hops = hops,
                            senderId = originDeviceId,
                            receiverId = receiverId
                        )
                        receivedSamples.add(sample)
                        val totalSent = sentTimestamps.size
                        val pdr = if (totalSent > 0) (receivedSamples.size.toFloat() / totalSent) * 100f else 0f
                        _liveProgress.value = "Progress: ${receivedSamples.size}/$totalSent Echoed (%.1f%%) | RTT: %d ms".format(pdr, rtt)
                    }
                }
                return true
            }
        }
        return false
    }

    private fun finishBenchmark(sessionId: String, totalSent: Int) {
        val receivedCount = receivedSamples.size
        val pdr = if (totalSent > 0) (receivedCount.toFloat() / totalSent) * 100f else 0f

        val latencies = receivedSamples.map { it.latencyMs }
        val minLat = latencies.minOrNull() ?: 0L
        val maxLat = latencies.maxOrNull() ?: 0L
        val avgLat = if (latencies.isNotEmpty()) latencies.average().toFloat() else 0f

        val csvFile = exportToCsv(sessionId, totalSent)

        val summary = BenchmarkSummary(
            sessionId = sessionId,
            totalSent = totalSent,
            totalReceived = receivedCount,
            packetDeliveryRatio = pdr,
            minLatencyMs = minLat,
            maxLatencyMs = maxLat,
            avgLatencyMs = avgLat,
            csvFilePath = csvFile.absolutePath
        )

        _lastSummary.value = summary
        _isRunning.value = false
        _liveProgress.value = "Completed! PDR: %.1f%% | Avg Latency: %.1f ms".format(pdr, avgLat)
    }

    private fun exportToCsv(sessionId: String, totalSent: Int): File {
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "benchmarks").apply { mkdirs() }
        val file = File(dir, "RelayNet_Benchmark_${sessionId}_$dateStr.csv")

        file.printWriter().use { out ->
            out.println("SessionId,SequenceNo,SendTimestamp,EchoTimestamp,RTT_ms,OneWayLatency_ms,HopCount,SenderId,ReceiverId,Status")
            val receivedMap = receivedSamples.associateBy { it.seqNo }

            for (seq in 1..totalSent) {
                val sample = receivedMap[seq]
                val sendTime = sentTimestamps[seq] ?: 0L
                if (sample != null) {
                    out.println("${sample.sessionId},${sample.seqNo},${sample.sendTimestamp},${sample.echoTimestamp},${sample.rttMs},${sample.latencyMs},${sample.hops},${sample.senderId},${sample.receiverId},DELIVERED")
                } else {
                    out.println("$sessionId,$seq,$sendTime,0,0,0,0,$myDeviceId,,DROPPED")
                }
            }
        }
        return file
    }
}
