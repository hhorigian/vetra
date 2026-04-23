/**
 * Vetra Utilities
 * Hubitat App — instala e gerencia automaticamente os utilitários Vetra.
 *
 * Incluído:
 *   - Certificado SSL wildcard (verifica e instala diariamente às 03:00)
 *   - Internet Check (Virtual Switch atualizado a cada 5 min via ping)
 *
 * Instalação:
 *   1. Hubitat → Apps Code → New App → colar este código → Save
 *   2. Apps → Add User App → Vetra Utilities → Done
 *   (nenhuma configuração necessária)
 */

import groovy.transform.Field

@Field static final String VETRA_URL   = "https://auth.vetra.center"
@Field static final String VETRA_TOKEN = "1C282A66-FAE2-4B89-839E-16FFDB564F1D"
@Field static final String PING_HOST   = "8.8.8.8"
@Field static final int    PING_COUNT  = 3

definition(
    name:           "Vetra Utilities",
    namespace:      "vetra",
    author:         "Vetra",
    description:    "Gerencia automaticamente o certificado SSL e o Virtual Switch de internet.",
    category:       "Utility",
    iconUrl:        "",
    iconX2Url:      "",
    singleInstance: true
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Vetra Utilities", install: true, uninstall: true) {

        section("Internet Check") {
            def sw = getChildDevice(internetDni())
            if (sw) {
                paragraph "Dispositivo: <b>${sw.displayName}</b> — ${sw.currentValue('switch')?.toUpperCase()}"
            } else {
                paragraph "Dispositivo: <b>será criado ao instalar</b>"
            }
            paragraph "Última verificação: <b>${state.netLastCheck ?: 'Nunca'}</b>"
            paragraph "Resultado: <b>${state.netLastResult ?: '-'}</b>"
        }

        section("Certificado SSL") {
            paragraph "Versão instalada: <b>${state.certInstalledVersion ?: 'Nenhum'}</b>"
            paragraph "Última verificação: <b>${state.certLastCheck ?: 'Nunca'}</b>"
            paragraph "Resultado: <b>${state.certLastResult ?: '-'}</b>"
        }

        section("Ações") {
            input "checkCertNow",  "button", title: "Verificar Cert Agora"
            input "forceInstall",  "button", title: "⚠️ Forçar Reinstalação do Cert"
            input "checkNetNow",   "button", title: "Verificar Internet Agora"
        }
    }
}

// ─── Lifecycle ───────────────────────────────────────────────────────────────

def installed() {
    log.info "[Vetra] Instalando..."
    initialize()
}

def updated() {
    log.info "[Vetra] Atualizando..."
    unschedule()
    initialize()
}

def initialize() {
    createInternetSwitch()

    runEvery5Minutes("checkInternet")
    runIn(5, "checkInternet")

    schedule("0 0 3 * * ?", "checkAndInstallCert")
    runIn(15, "checkAndInstallCert")

    log.info "[Vetra] Pronto — internet check a cada 5 min, cert check diário às 03:00"
}

def appButtonHandler(btn) {
    switch (btn) {
        case "checkCertNow":
            log.info "[Vetra] Verificação de cert manual"
            checkAndInstallCert()
            break
        case "forceInstall":
            log.warn "[Vetra] Forçando reinstalação do cert"
            state.certInstalledVersion = null
            checkAndInstallCert()
            break
        case "checkNetNow":
            log.info "[Vetra] Verificação de internet manual"
            checkInternet()
            break
    }
}

def uninstalled() {
    log.info "[Vetra] Desinstalando..."
    unschedule()
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

// ─── Internet Check ──────────────────────────────────────────────────────────

private String internetDni() { "vetra-internet-check-${app.id}" }

def createInternetSwitch() {
    def existing = getChildDevice(internetDni())
    if (existing) {
        log.info "[Vetra/Net] Virtual Switch já existe: ${existing.displayName}"
        return existing
    }
    try {
        def sw = addChildDevice("hubitat", "Virtual Switch", internetDni(), null, [
            name: "Internet Check", label: "Internet Check", isComponent: false
        ])
        sw.on()
        log.info "[Vetra/Net] Virtual Switch criado: ${sw.displayName}"
        return sw
    } catch (Exception e) {
        log.error "[Vetra/Net] Erro ao criar Virtual Switch: ${e.message}"
        return null
    }
}

def checkInternet() {
    def sw = getChildDevice(internetDni())
    if (!sw) {
        sw = createInternetSwitch()
        if (!sw) { log.error "[Vetra/Net] Virtual Switch não encontrado e não pode ser criado"; return }
    }

    state.netLastCheck = new Date().format("dd/MM/yyyy HH:mm:ss")

    try {
        def ping = hubitat.helper.NetworkUtils.ping(PING_HOST, PING_COUNT)

        if (ping && ping.packetLoss != null && ping.packetLoss < 100) {
            def rtt = ping.rttAvg ? "${ping.rttAvg}ms" : "N/A"
            state.netLastResult = "ONLINE — ${rtt}"
            log.info "[Vetra/Net] ONLINE (${rtt}, loss ${ping.packetLoss}%)"
            if (sw.currentValue('switch') != 'on') sw.on()
        } else {
            def loss = ping?.packetLoss != null ? "${ping.packetLoss}%" : "total"
            state.netLastResult = "OFFLINE (${loss} packet loss)"
            log.warn "[Vetra/Net] OFFLINE (${loss} packet loss)"
            if (sw.currentValue('switch') != 'off') sw.off()
        }
    } catch (Exception e) {
        state.netLastResult = "ERRO: ${e.message}"
        log.error "[Vetra/Net] Erro no ping: ${e.message}"
        if (sw.currentValue('switch') != 'off') sw.off()
    }
}

// ─── Cert Manager ────────────────────────────────────────────────────────────

def checkAndInstallCert() {
    log.info "[Vetra/Cert] Verificando cert..."
    state.certLastCheck = new Date().format("dd/MM/yyyy HH:mm:ss")

    try {
        httpGet([uri: "${VETRA_URL}/functions/v1/hubCert?token=${VETRA_TOKEN}", timeout: 15]) { resp ->
            if (resp.status != 200) {
                def msg = "Erro HTTP ${resp.status}"
                log.error "[Vetra/Cert] ${msg}"
                state.certLastResult = msg
                return
            }

            def data    = resp.data
            def version = data?.version?.toString()
            def cert    = data?.cert?.toString()
            def key     = data?.key?.toString()

            if (!version || !cert || !key) {
                def msg = "Resposta inválida do servidor"
                log.warn "[Vetra/Cert] ${msg}: ${data}"
                state.certLastResult = msg
                return
            }

            if (version == state.certInstalledVersion) {
                def msg = "Cert já atualizado (v${version})"
                log.info "[Vetra/Cert] ${msg}"
                state.certLastResult = msg
                return
            }

            installCert(cert, key, version)
        }
    } catch (Exception e) {
        def msg = "Falha ao conectar ao Vetra: ${e.message}"
        log.error "[Vetra/Cert] ${msg}"
        state.certLastResult = msg
    }
}

def installCert(String cert, String key, String version) {
    log.info "[Vetra/Cert] Instalando versão ${version}..."

    try {
        httpPost([
            uri:             "http://localhost:8080/hub/advanced/certificate/save",
            body:            [certificate: cert, privateKey: key],
            contentType:     "application/x-www-form-urlencoded",
            timeout:         30,
            followRedirects: false
        ]) { resp ->
            if (resp.status == 200 || resp.status == 302) {
                state.certInstalledVersion = version
                def msg = "✅ Cert instalado (v${version})"
                log.info "[Vetra/Cert] ${msg}"
                state.certLastResult = msg
                runIn(60, rebootHub)
                log.warn "[Vetra/Cert] Hub vai reiniciar em 60s para ativar o novo cert"
            } else {
                def msg = "Falha ao instalar: HTTP ${resp.status}"
                log.error "[Vetra/Cert] ${msg}"
                state.certLastResult = msg
            }
        }
    } catch (Exception e) {
        def msg = "Erro ao instalar cert: ${e.message}"
        log.error "[Vetra/Cert] ${msg}"
        state.certLastResult = msg
    }
}

def rebootHub() {
    log.warn "[Vetra/Cert] Reiniciando hub para ativar novo cert SSL..."
    httpPost([uri: "http://localhost:8080/hub/reboot", timeout: 10]) { resp ->
        log.info "[Vetra/Cert] Reboot solicitado: HTTP ${resp.status}"
    }
}
