package ru.racelab.phone.ble

data class BleDeviceItem(val address: String, val name: String, val rssi: Int)

data class BleUiState(
    val scanning: Boolean = false,
    val status: String = "Отключено",
    val devices: List<BleDeviceItem> = emptyList(),
    val connectedAddress: String? = null
)
