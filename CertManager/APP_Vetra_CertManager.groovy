/**
 * Vetra Cert Manager
 * Hubitat App — verifica e instala automaticamente o cert SSL wildcard do Vetra.
 *
 * Instalação:
 *   1. Hubitat → Apps Code → New App → colar este código → Save
 *   2. Apps → Add User App → Vetra Cert Manager
 *   3. Preencher a URL do Supabase e o Hub Token
 *   4. Clicar em "Verificar Agora" para testar
 *
 * Funcionamento:
 *   - Verifica a cada 24h se o cert mudou (compara version)
 *   - Se mudou: baixa e instala automaticamente via API interna da Hubitat
 *   - Loga o resultado no log da Hubitat
 */

definition(
    name:        "Vetra Cert Manager",
    namespace:   "vetra",
    author:      "Vetra",
    description: "Atualiza automaticamente o certificado SSL wildcard do Vetra na Hubitat.",
    category:    "Utility",
    iconUrl:     "",
    iconX2Url:   ""
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Vetra Cert Manager", install: true, uninstall: true) {

        section("Configuração") {
            input "supabaseUrl", "text",
                title: "URL do Supabase (ex: https://xxx.supabase.co)",
                required: true

            input "hubToken", "text",
                title: "Hub Cert Token (fornecido pela Vetra)",
                required: true
        }

        section("Status") {
            def installedVersion = state.installedVersion ?: "Nenhum"
            def lastCheck       = state.lastCheck ?: "Nunca"
            def lastResult      = state.lastResult ?: "-"

            paragraph "Versão instalada: <b>${installedVersion}</b>"
            paragraph "Última verificação: <b>${lastCheck}</b>"
            paragraph "Resultado: <b>${lastResult}</b>"
        }

        section("Ações") {
            input "checkNow", "button", title: "Verificar e Instalar Agora"
            input "forceInstall", "button", title: "⚠️ Forçar Reinstalação"
        }
    }
}

def installed() {
    log.info "[VetraCert] App instalado"
    initialize()
}

def updated() {
    log.info "[VetraCert] App atualizado"
    unschedule()
    initialize()
}

def initialize() {
    // Verifica uma vez ao instalar/atualizar
    runIn(5, checkAndInstallCert)
    // Agenda verificação diária às 03:00
    schedule("0 0 3 * * ?", checkAndInstallCert)
    log.info "[VetraCert] Verificação diária agendada às 03:00"
}

def appButtonHandler(btn) {
    if (btn == "checkNow") {
        log.info "[VetraCert] Verificação manual disparada"
        checkAndInstallCert()
    } else if (btn == "forceInstall") {
        log.warn "[VetraCert] Forçando reinstalação — limpando versão instalada"
        state.installedVersion = null
        checkAndInstallCert()
    }
}

def checkAndInstallCert() {
    log.info "[VetraCert] Verificando cert no Vetra..."
    state.lastCheck = new Date().format("dd/MM/yyyy HH:mm:ss")

    def certUrl = "${supabaseUrl}/functions/v1/hubCert?token=${hubToken}"

    try {
        httpGet([uri: certUrl, timeout: 15]) { resp ->
            if (resp.status != 200) {
                def msg = "Erro ao buscar cert: HTTP ${resp.status}"
                log.error "[VetraCert] ${msg}"
                state.lastResult = msg
                return
            }

            def data    = resp.data
            def version = data?.version?.toString()
            def cert    = data?.cert?.toString()
            def key     = data?.key?.toString()

            if (!version || !cert || !key) {
                def msg = "Resposta inválida do servidor"
                log.warn "[VetraCert] ${msg}: ${data}"
                state.lastResult = msg
                return
            }

            log.info "[VetraCert] Cert disponível: versão ${version} | instalada: ${state.installedVersion}"

            if (version == state.installedVersion) {
                def msg = "Cert já atualizado (v${version})"
                log.info "[VetraCert] ${msg}"
                state.lastResult = msg
                return
            }

            // Versão diferente — instala
            installCert(cert, key, version)
        }
    } catch (Exception e) {
        def msg = "Falha ao conectar ao Vetra: ${e.message}"
        log.error "[VetraCert] ${msg}"
        state.lastResult = msg
    }
}

def installCert(String cert, String key, String version) {
    log.info "[VetraCert] Instalando cert versão ${version}..."

    // Endpoint interno da Hubitat para salvar o certificado SSL
    // Equivalente a: Hubitat UI → Settings → Hub Details → SSL Certificate
    def installUrl = "http://localhost:8080/hub/advanced/certificate/save"

    def body = [
        certificate: cert,
        privateKey:  key
    ]

    try {
        httpPost([
            uri:                  installUrl,
            body:                 body,
            contentType:          "application/x-www-form-urlencoded",
            timeout:              30,
            followRedirects:      false  // 302 é resposta normal da Hubitat após salvar
        ]) { resp ->
            // 200 ou 302 = sucesso (Hubitat redireciona após salvar)
            if (resp.status == 200 || resp.status == 302) {
                state.installedVersion = version
                def msg = "✅ Cert instalado com sucesso (v${version})"
                log.info "[VetraCert] ${msg}"
                state.lastResult = msg

                // Agenda reboot da Hubitat em 60s para ativar o novo cert
                runIn(60, rebootHub)
                log.warn "[VetraCert] Hub vai reiniciar em 60 segundos para ativar o cert"
            } else {
                def msg = "Falha ao instalar cert: HTTP ${resp.status}"
                log.error "[VetraCert] ${msg}"
                state.lastResult = msg
            }
        }
    } catch (Exception e) {
        def msg = "Erro ao instalar cert: ${e.message}"
        log.error "[VetraCert] ${msg}"
        state.lastResult = msg
    }
}

def rebootHub() {
    log.warn "[VetraCert] Reiniciando hub para ativar novo cert SSL..."
    httpPost([
        uri:     "http://localhost:8080/hub/reboot",
        timeout: 10
    ]) { resp ->
        log.info "[VetraCert] Reboot solicitado: HTTP ${resp.status}"
    }
}
