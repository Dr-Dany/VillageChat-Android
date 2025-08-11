
package com.example.villagechat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.charset.StandardCharsets
import java.util.*

class MainActivity : ComponentActivity() {
    private val vm: VCViewModel by viewModels()

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // If permissions granted, we can proceed
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askPerms()

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize().background(Color(0xFFF1F5F9))) {
                    VillageUI(vm = vm, startAdvertise = { name -> startAdvertising(name) },
                        startDiscover = { startDiscovery() },
                        stopAll = { stopAll() },
                        send = { msg -> sendToAll(msg) })
                }
            }
        }
    }

    private fun askPerms(){
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val sdk = android.os.Build.VERSION.SDK_INT
        if (sdk >= 31) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        val need = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (need.isNotEmpty()) requestPermissions.launch(need.toTypedArray())
    }

    private val STRATEGY = Strategy.P2P_CLUSTER
    private val connections by lazy { Nearby.getConnectionsClient(this) }
    private val endpoints = mutableMapOf<String, String>() // id -> name

    private fun startAdvertising(nickname: String) {
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connections.startAdvertising(
            nickname, packageName, connectionLifecycle, advertisingOptions
        ).addOnSuccessListener { vm.setStatus("En attente des voisins…") }
            .addOnFailureListener { vm.setStatus("Erreur publicité : ${it.message}") }
    }

    private fun startDiscovery() {
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connections.startDiscovery(
            packageName, endpointDiscovery, discoveryOptions
        ).addOnSuccessListener { vm.setStatus("Recherche de voisins…") }
            .addOnFailureListener { vm.setStatus("Erreur recherche : ${it.message}") }
    }

    private val connectionLifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Auto-accept for simplicity
            connections.acceptConnection(endpointId, payloadCallback)
            endpoints[endpointId] = info.endpointName
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                vm.setStatus("Connecté à ${endpoints[endpointId] ?: endpointId}")
                vm.addSystem("✅ Connecté à ${endpoints[endpointId] ?: endpointId}")
            } else {
                vm.addSystem("❌ Connexion échouée")
            }
        }
        override fun onDisconnected(endpointId: String) {
            vm.addSystem("🔌 Déconnecté de ${endpoints[endpointId] ?: endpointId}")
            endpoints.remove(endpointId)
        }
    }

    private val endpointDiscovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // Connect immediately
            connections.requestConnection("Village-" + UUID.randomUUID().toString().take(4), endpointId, connectionLifecycle)
        }
        override fun onEndpointLost(endpointId: String) {}
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let {
                val text = String(it, StandardCharsets.UTF_8)
                vm.addMsg("👤 ${endpoints[endpointId] ?: endpointId}", text)
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun sendToAll(text: String) {
        val data = Payload.fromBytes(text.toByteArray(StandardCharsets.UTF_8))
        for (id in endpoints.keys) connections.sendPayload(id, data)
        vm.addMsg("Moi", text)
    }

    private fun stopAll() {
        connections.stopAdvertising()
        connections.stopDiscovery()
        connections.stopAllEndpoints()
        vm.setStatus("Arrêté")
    }
}

class VCViewModel : androidx.lifecycle.ViewModel() {
    private val _status = MutableStateFlow("Prêt")
    val status = _status.asStateFlow()
    private val _msgs = MutableStateFlow(listOf<Pair<String,String>>())
    val msgs = _msgs.asStateFlow()

    fun setStatus(s: String){ _status.value = s }
    fun addMsg(who: String, text: String){ _msgs.value = _msgs.value + (who to text) }
    fun addSystem(text: String){ _msgs.value = _msgs.value + ("Système" to text) }
}

@Composable
fun VillageUI(vm: VCViewModel,
              startAdvertise: (String)->Unit,
              startDiscover: ()->Unit,
              stopAll: ()->Unit,
              send: (String)->Unit) {
    val status by vm.status.collectAsState(initial = "Prêt")
    val msgs by vm.msgs.collectAsState(initial = emptyList())
    var name by remember { mutableStateOf("VillageUser") }
    var text by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("VillageChat", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            AssistChip(onClick = {}, label = { Text(status) })
        }
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Votre nom") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { startAdvertise(name) }, modifier = Modifier.weight(1f)) { Text("Créer un salon") }
                    Button(onClick = { startDiscover() }, modifier = Modifier.weight(1f)) { Text("Rejoindre") }
                    OutlinedButton(onClick = { stopAll() }) { Text("Arrêter") }
                }
                Text("Aucun internet nécessaire. Portée ~10-30m, multi-appareils (mesh simplifié).", color = Color.Gray)
            }
        }
        ElevatedCard(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
                items(msgs) { (who, msg) ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(who, fontWeight = FontWeight.SemiBold)
                        Text(msg)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Message") }, modifier = Modifier.weight(1f))
            Button(onClick = { if (text.isNotBlank()) { send(text); text = "" } }) { Text("Envoyer") }
        }
        Text("Partage : après installation, partagez l'APK via Bluetooth / WhatsApp / Drive.", color = Color.Gray, modifier = Modifier.padding(4.dp))
    }
}
