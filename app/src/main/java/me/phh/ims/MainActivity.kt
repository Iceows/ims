package me.phh.ims

import android.annotation.SuppressLint
import android.content.Context
import android.net.*
import android.net.eap.EapSessionConfig
import android.net.ipsec.ike.*
import android.os.Build.VERSION_CODES
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.service.autofill.TextValueSanitizer
import android.system.OsConstants.AF_INET
import android.system.OsConstants.AF_INET6
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Base64
import android.util.Log
import android.widget.TextView
import java.io.BufferedInputStream
import java.io.FileDescriptor
import java.io.StringBufferInputStream
import java.io.StringReader
import java.net.*
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.system.exitProcess
import android.telephony.SubscriptionInfo
import android.os.Build.VERSION_CODES.LOLLIPOP_MR1
import android.os.Build










fun String.toMD5(): String {
    val bytes = MessageDigest.getInstance("MD5").digest(this.toByteArray())
    return bytes.toHex()
}

fun ByteArray.toHex(): String {
    return joinToString("") { "%02x".format(it) }
}

fun BufferedInputStream.lines(): Iterator<String> {
    return object: Iterator<String> {
        val lineBuffer = ByteArray(512)
        override fun hasNext(): Boolean {
            return true
        }

        override fun next(): String {
            mark(lineBuffer.size*2)
            read(lineBuffer)
            var pos = -1
            for(i in lineBuffer.indices) {
                if(lineBuffer[i] == '\n'.toByte()) {
                    pos = i
                    break
                }
            }
            if(pos == -1) throw Exception("Buffered read failed, increase lineBuffer size?")
            reset()

            val thisBuffer = ByteArray(pos+1)
            read(thisBuffer, 0, pos+1)
            return String(thisBuffer, Charsets.US_ASCII)
        }
    }
}

class MainActivity : AppCompatActivity() {
    // in Key: Value extract value
    val extractValueRegex = Regex("^[^:]*: *(.*)")
    fun extractValue(line: String): String? {
        val v = extractValueRegex.find(line) ?: return null
        return v.groupValues[1]
    }

    var ref: IkeSession? = null
    //var na: NetworkAgent? = null

    @SuppressLint("NewApi")
    fun connectIke(ctxt: Context) {
        val ipsecManager = ctxt.getSystemService(IpSecManager::class.java)
        val nm = ctxt.getSystemService(ConnectivityManager::class.java)

        val tm = ctxt.getSystemService(TelephonyManager::class.java)
        var network: Network? = null

        nm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                Log.d("PHH", "Got network available $n")
                network = n
            }
        })

        val mcc = tm.simOperator.substring(0 until 3)
        var mnc = tm.simOperator.substring(3)
        if (mnc.length == 2) mnc = "0$mnc"
        val imsi = tm.subscriberId

        Log.d("PHH", "Got mcc $mcc mnc $mnc imsi $imsi")

        val ikeParamsBuilder = IkeSessionParams.Builder()
        //paramsBuilder.setDscp(26)
        ikeParamsBuilder.setServerHostname("epdg.epc.mnc$mnc.mcc$mcc.pub.3gppnetwork.org")
        ikeParamsBuilder.setLocalIdentification(IkeRfc822AddrIdentification("0$imsi@nai.epc.mnc$mnc.mcc$mcc.pub.3gppnetwork.org"))
        ikeParamsBuilder.setRemoteIdentification(IkeFqdnIdentification("ims"))
        ikeParamsBuilder.setAuthEap(
            null,
            EapSessionConfig.Builder()
                .setEapAkaConfig(1, TelephonyManager.APPTYPE_USIM)
                .setEapIdentity("0$imsi@nai.epc.mnc$mnc.mcc$mcc.pub.3gppnetwork.org".toByteArray())
                .build()
        )
        // This SA proposal works on Bouygues telecom 208 20
        ikeParamsBuilder.addIkeSaProposal(
            IkeSaProposal.Builder()
                .addDhGroup(SaProposal.DH_GROUP_2048_BIT_MODP)
                .addEncryptionAlgorithm(SaProposal.ENCRYPTION_ALGORITHM_AES_CBC, 256)
                .addIntegrityAlgorithm(SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_256_128)
                .addPseudorandomFunction(SaProposal.PSEUDORANDOM_FUNCTION_SHA2_256)
                .build()
        )
        // This SA proposal works on Free Mobile 208 15
        ikeParamsBuilder.addIkeSaProposal(
            IkeSaProposal.Builder()
                .addDhGroup(SaProposal.DH_GROUP_1024_BIT_MODP)
                .addEncryptionAlgorithm(SaProposal.ENCRYPTION_ALGORITHM_AES_CBC, 128)
                .addIntegrityAlgorithm(SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA1_96)
                .addPseudorandomFunction(SaProposal.PSEUDORANDOM_FUNCTION_HMAC_SHA1)
                .build()
        )
        //paramsBuilder.setNetwork()
        ikeParamsBuilder.addIkeOption(IkeSessionParams.IKE_OPTION_ACCEPT_ANY_REMOTE_ID)
        //ikeParamsBuilder.addIkeOption(IkeSessionParams.IKE_OPTION_MOBIKE)
        //set lifetime
        //set retransmission
        //set dpd delay

        // Optional in Iwlan, but why would we want any other auth that EAP?!?
        ikeParamsBuilder.addIkeOption(IkeSessionParams.IKE_OPTION_EAP_ONLY_AUTH)
        ikeParamsBuilder.javaClass.getMethod("addPcscfServerRequest", Int::class.java)
            .invoke(ikeParamsBuilder, AF_INET)
        ikeParamsBuilder.javaClass.getMethod("addPcscfServerRequest", Int::class.java)
            .invoke(ikeParamsBuilder, AF_INET6)
        // Add Ike 3GPP extensions?
        // Set NATT keepalive?
        val childParamsBuilder = TunnelModeChildSessionParams.Builder()
        // This child SA proposal works on Bouygues telecom 208 20
        /*childParamsBuilder.addChildSaProposal(
            ChildSaProposal.Builder()
                .addEncryptionAlgorithm(SaProposal.ENCRYPTION_ALGORITHM_AES_CBC, 256)
                .addIntegrityAlgorithm(SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_256_128)
                .build()
        )*/
        // This child SA proposal works on Free Mobile 208 15
        childParamsBuilder.addChildSaProposal(
            ChildSaProposal.Builder()
                .addEncryptionAlgorithm(SaProposal.ENCRYPTION_ALGORITHM_AES_CBC, 128)
                .addIntegrityAlgorithm(SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA1_96)
                .build()
        )
        // set child lifetime
        // set handover infos (original ipv4/ipv6)
        childParamsBuilder.addInternalAddressRequest(AF_INET)
        childParamsBuilder.addInternalAddressRequest(AF_INET6)
        childParamsBuilder.addInternalDnsServerRequest(AF_INET)
        childParamsBuilder.addInternalDnsServerRequest(AF_INET6)
        // set traffic selector?

        var ipsecTunnel: Object? = null
        ipsecManager.javaClass.getMethod(
            "createIpSecTunnelInterface",
            InetAddress::class.java,
            InetAddress::class.java,
            Network::class.java
        )

        val handlerThread = HandlerThread("PHH IMS").also { it.start() }
        val handler = Handler(handlerThread.looper)
        var pcscf: InetAddress? = null
        var sessionConfiguration: IkeSessionConfiguration? = null
        var childConfiguration: ChildSessionConfiguration? = null
        ref = IkeSession(ctxt, ikeParamsBuilder.build(), childParamsBuilder.build(),
            { p0 ->
                handler.post(object : Runnable {
                    override fun run() {
                        try {
                            p0.run()
                        } catch (t: Throwable) {
                            Log.d("PHH", "Executor failed with", t)
                        }
                    }
                })
            }, object : IkeSessionCallback {
                override fun onOpened(p0: IkeSessionConfiguration) {
                    Log.d(
                        "PHH",
                        "IKE session opened ${p0.ikeSessionConnectionInfo.localAddress} ${p0.ikeSessionConnectionInfo.remoteAddress}"
                    )
                    Log.d("PHH", "Bound network is ${nm.boundNetworkForProcess}")
                    ipsecTunnel = ipsecManager.javaClass
                        .getMethod(
                            "createIpSecTunnelInterface",
                            InetAddress::class.java,
                            InetAddress::class.java,
                            Network::class.java
                        )
                        .invoke(
                            ipsecManager,
                            p0.ikeSessionConnectionInfo.localAddress,
                            p0.ikeSessionConnectionInfo.remoteAddress,
                            network
                        ) as Object

                    val _pcscf = p0.javaClass.getMethod("getPcscfServers")
                        .invoke(p0) as List<InetAddress>
                    Log.d("PHH", "IKE session pcscf ${_pcscf.toList()}")
                    pcscf = _pcscf[0]
                    sessionConfiguration = p0
                }

                override fun onClosed() {
                    Log.d("PHH", "IKE session closed")
                }
            }, object : ChildSessionCallback {
                override fun onOpened(p0: ChildSessionConfiguration) {
                    val internalAddress = p0.javaClass.getMethod("getInternalAddresses")
                        .invoke(p0) as List<LinkAddress>
                    Log.d("PHH", "IKE child session opened $p0 ${internalAddress.toList()}")
                    for (addr in internalAddress) {
                        Class.forName("android.net.IpSecManager\$IpSecTunnelInterface")
                            .getMethod("addAddress", InetAddress::class.java, Int::class.java)
                            .invoke(ipsecTunnel, addr.address, addr.prefixLength)
                    }
                    childConfiguration = p0

                    Log.d("PHH", "VoWifi tunnel ready at interface ${ipsecTunnel!!.javaClass.getMethod("getInterfaceName")}")

                    /*
                    Following code manages to create a Network from Android PoV, though it requires access to private APIs (or rather SystemApi)

                    val capabilitiesBuilder =
                        Class.forName("android.net.NetworkCapabilities\$Builder")
                            .getConstructor().newInstance()
                    capabilitiesBuilder.javaClass.getMethod("addTransportType", Int::class.java)
                        .invoke(capabilitiesBuilder, NetworkCapabilities.TRANSPORT_WIFI)
                    capabilitiesBuilder.javaClass.getMethod("addCapability", Int::class.java)
                        .invoke(capabilitiesBuilder, NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    capabilitiesBuilder.javaClass.getMethod("addCapability", Int::class.java)
                        .invoke(
                            capabilitiesBuilder,
                            NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING
                        )
                    capabilitiesBuilder.javaClass.getMethod("addCapability", Int::class.java)
                        .invoke(
                            capabilitiesBuilder,
                            NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED
                        )
                    capabilitiesBuilder.javaClass.getMethod("addCapability", Int::class.java)
                        .invoke(capabilitiesBuilder, NetworkCapabilities.NET_CAPABILITY_XCAP)

                    val capabilities = capabilitiesBuilder.javaClass.getMethod("build")
                        .invoke(capabilitiesBuilder) as NetworkCapabilities
                    val lp = LinkProperties()
                    lp.interfaceName = ipsecTunnel!!.javaClass.getMethod("getInterfaceName")
                        .invoke(ipsecTunnel) as String
                    lp.javaClass.getMethod("setLinkAddresses", Collection::class.java)
                        .invoke(lp, internalAddress)
                    val ipv6DefaultPrefix =
                        IpPrefix::class.java.getConstructor(
                            InetAddress::class.java,
                            Int::class.java
                        )
                            .newInstance(Inet6Address.getByName("::"), 0)
                    val route =
                        RouteInfo::class.java.getConstructor(
                            IpPrefix::class.java,
                            InetAddress::class.java,
                            String::class.java,
                            Int::class.java
                        )
                            .newInstance(
                                ipv6DefaultPrefix,
                                InetAddress.getByName("::"),
                                lp.interfaceName,
                                1
                            )
                    lp.addRoute(route)
                    lp.javaClass.getMethod("addPcscfServer", InetAddress::class.java)
                        .invoke(lp, pcscf)

                    val configBuilder = Class.forName("android.net.NetworkAgentConfig\$Builder")
                        .getConstructor().newInstance()
                    configBuilder.javaClass.getMethod("setLegacyTypeName", String::class.java)
                        .invoke(configBuilder, "USB")
                    configBuilder.javaClass.getMethod(
                        "setNat64DetectionEnabled",
                        Boolean::class.java
                    ).invoke(configBuilder, false)
                    configBuilder.javaClass.getMethod(
                        "setProvisioningNotificationEnabled",
                        Boolean::class.java
                    ).invoke(configBuilder, false)
                    val config = configBuilder.javaClass.getMethod("build")
                        .invoke(configBuilder)


                    val networkProvider = Class.forName("android.net.NetworkProvider").getConstructor(Context::class.java, Looper::class.java, String::class.java).newInstance(ctxt, handlerThread.looper, "PHH-IMS")

                    na = object : NetworkAgent(
                        ctxt,
                        handlerThread.looper,
                        "PHH-IMS",
                        capabilities,
                        lp,
                        10,
                        config,
                        networkProvider
                    ) {
                    }

                    na!!.register()
                    na!!.markConnected()*/
                }

                override fun onClosed() {
                    Log.d("PHH", "IKE child session closed")
                }

                override fun onIpSecTransformCreated(p0: IpSecTransform, p1: Int) {
                    Log.d("PHH", "IPSec session created $p0 $p1")

                    ipsecManager.javaClass
                        .getMethod(
                            "applyTunnelModeTransform",
                            Class.forName("android.net.IpSecManager\$IpSecTunnelInterface"),
                            Int::class.java,
                            IpSecTransform::class.java
                        )
                        .invoke(ipsecManager, ipsecTunnel, p1, p0)
                }

                override fun onIpSecTransformDeleted(p0: IpSecTransform, p1: Int) {
                    Log.d("PHH", "IPSec session deleted $p0 $p1")
                }
            })
    }

    fun updateStatus(str: String) {
        Log.d("PHH", "upd : " + str)
        runOnUiThread {
            val status = findViewById<TextView>(R.id.status)
            status.text = str + "\n" + status.text
        }
    }


    fun supportMSim(): Boolean {
        return Build.VERSION.SDK_INT >= LOLLIPOP_MR1
    }

    fun getSmsManager(subId: Int): SmsManager? {
        return if (supportMSim()) {
            SmsManager.getSmsManagerForSubscriptionId(subId)
        } else {
            SmsManager.getDefault()
        }
    }


    @SuppressLint("HardwareIds", "MissingPermission")
    fun launchIms(network: Network) {
        updateStatus("Launching SIP")

        val ipsecManager = getSystemService(IpSecManager::class.java)
        val nm = getSystemService(ConnectivityManager::class.java)
        val tm = getSystemService(TelephonyManager::class.java)
        val sm = getSystemService(SubscriptionManager::class.java)
        val subscriptions = sm.activeSubscriptionInfoList
        val activeSubscription = subscriptions[0]
        val subId = activeSubscription.subscriptionId
        val imei = tm.getDeviceId(activeSubscription.simSlotIndex)
        //val smsManager = getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
        val smsManager = getSmsManager(subId)

        val smscStr = smsManager?.smscAddress
        val smscMatchRegex = Regex("([0-9]+)")
        val smsc = smscMatchRegex.find(smscStr!!)!!.groupValues[1]


        val mcc = tm.simOperator.substring(0 until 3)
        var mnc = tm.simOperator.substring(3)
        if (mnc.length == 2) mnc = "0$mnc"
        val imsi = tm.subscriberId

        Thread.sleep(3000)
        Log.d("PHH", "XCAP+WIFI transport available ${network}")
        val lp = nm.getLinkProperties(network)
        val caps = nm.getNetworkCapabilities(network)
        Log.d("PHH", " caps = $caps, lp = $lp")
        val realm = "ims.mnc$mnc.mcc$mcc.3gppnetwork.org"
        val user = "$imsi@ims.mnc$mnc.mcc$mcc.3gppnetwork.org"

        Log.d("PHH", "realm: ${realm} - user: ${user}")

        var pcscfs = lp!!.javaClass.getMethod("getPcscfServers").invoke(lp) as List<InetAddress>

        if (pcscfs.size == 0) {
            Log.d("PHH", "no Pcscf Servers !! - Try hardcode SFR server")

            // SFR pcscfr adresse
            var myListSFR:  List<InetAddress> =  listOf(InetAddress.getByName("2a02:8400:20:225::5"),InetAddress.getByName("2a02:8400:20:1206::5"))

            pcscfs=myListSFR
        }

        val pcscf = pcscfs[0]

        val myAddr = lp.linkAddresses[0].address.hostAddress
        var tag =
            "a" + Random.Default.nextBytes(6).map { String.format("%02x", it) }
                .joinToString("")
        var branch = "z9hG4bK_" + Random.Default.nextBytes(6)
            .map { String.format("%02x", it) }.joinToString("")
        var callId =
            "a" + Random.Default.nextBytes(6).map { String.format("%02x", it) }
                .joinToString("")
        Log.d("PHH", "My addr $myAddr ; tag $tag; branch $branch ; callId $callId")
        try {
            updateStatus("Connecting to SIP")

            val socketFactory = network.socketFactory
            val socket = socketFactory.createSocket(pcscf, 5060)

            updateStatus("Registering 1")

            Log.d("PHH", "Socket opened!")
            val myAddr2 = socket.localAddress.hostAddress

            val socketInIpsec = socketFactory.createSocket()
            socketInIpsec.bind(InetSocketAddress(socket.localAddress, 0))
            val serverSocket = ServerSocket()
            val serverSocketFd = serverSocket.javaClass.getMethod("getFileDescriptor\$").invoke(serverSocket) as FileDescriptor
            serverSocket.bind(InetSocketAddress(socket.localAddress, socketInIpsec.localPort+1))
            network.bindSocket(serverSocketFd)

            val writer = socket.getOutputStream()
            val reader = socket.getInputStream().bufferedReader()
            val localPort = socketInIpsec.localPort

            val mySPI1 =
                ipsecManager.allocateSecurityParameterIndex(lp.linkAddresses[0].address)
            val mySPI2 = ipsecManager.allocateSecurityParameterIndex(lp.linkAddresses[0].address, mySPI1.spi + 1)
            //val secClient = "Security-Client: ipsec-3gpp;prot=esp;mod=trans;spi-c=${mySPI1.spi};spi-s=${mySPI2.spi};port-c=${localPort};port-s=${serverSocket.localPort};ealg=aes-cbc;alg=hmac-sha-1-96"
            val secClient = "Security-Client: ipsec-3gpp;prot=esp;mod=trans;spi-c=${mySPI1.spi};spi-s=${mySPI2.spi};port-c=${localPort};port-s=${serverSocket.localPort};ealg=null;alg=hmac-sha-1-96"
            //val secClient = "Security-Client: ipsec-3gpp;prot=esp;mod=trans;spi-c=${mySPI1.spi};spi-s=${mySPI2.spi};port-c=${localPort};port-s=${serverSocket.localPort};ealg=null;alg=hmac-sha-1-96, ipsec-3gpp;prot=esp;mod=trans;spi-c=${mySPI1.spi};spi-s=${mySPI2.spi};port-c=${localPort};port-s=${serverSocket.localPort};ealg=aes-cbc;alg=hmac-sha-1-96"
            // Contact +sip.instance="<urn:gsma:imei:86687905-321566-0>";
            val imeiStr = "86687905-321566-0"//imei.substring(0, 7) + "-" + imei.substring(7, 13) + "-" + imei.substring(13,14)
            val msg = """
                            REGISTER sip:ims.mnc$mnc.mcc$mcc.3gppnetwork.org SIP/2.0
                            Via: SIP/2.0/TCP [$myAddr2]:${socket.localPort};branch=$branch;rport
                            From: <sip:$imsi@ims.mnc$mnc.mcc$mcc.3gppnetwork.org>;tag=$tag
                            To: <sip:$imsi@ims.mnc$mnc.mcc$mcc.3gppnetwork.org>
                            Call-ID: $callId
                            Max-Forwards: 70
                            Expires: 600000
                            User-Agent: Xiaomi__Android_12_MIUI220114
                            Contact: <sip:$imsi@[$myAddr2]:${socket.localPort};transport=tcp>;expires=600000;+sip.instance="<urn:gsma:imei:$imeiStr>";+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel";+g.3gpp.smsip;audio
                            Supported: path, gruu, sec-agree
                            Allow: INVITE, ACK, CANCEL, BYE, UPDATE, REFER, NOTIFY, MESSAGE, PRACK, OPTIONS
                            Authorization: Digest username="$imsi@ims.mnc$mnc.mcc$mcc.3gppnetwork.org",realm="ims.mnc$mnc.mcc$mcc.3gppnetwork.org",nonce="",uri="sip:ims.mnc$mnc.mcc$mcc.3gppnetwork.org",response="",algorithm=AKAv1-MD5
                            Require: sec-agree
                            Proxy-Require: sec-agree
                            $secClient
                            CSeq: 1 REGISTER
                            Content-Length: 0
                        """.trimIndent()

            Log.d("PHH", "Sending $msg")

            writer.write(msg.replace("\n", "\r\n").toByteArray())
            writer.write("\r\n".toByteArray())
            writer.write("\r\n".toByteArray())

            var lines = mutableListOf<String>()
            for (line in reader.lines()) {
                lines.add(line.trim())
                Log.d("PHH", "Received < $line")
                if (line.trim() == "") break
            }
            socket.close()
            Log.d("PHH", "Socket closed!")

            updateStatus("Register 1 answered with ${lines[0]}")

            val securityServer =
                lines.find { it.toLowerCase().contains("security-server") }!!
                    .replace("Security-Server: ", "")
            val opaqueRegex = Regex(".*opaque=(\"[^\"]*\").*")
            val opaqueLine = lines.find { it.contains("opaque") }
            val opaque =
                if (opaqueLine != null) opaqueRegex.find(opaqueLine)!!.groupValues[1] else null

            val nonceRegex = Regex(".*nonce=\"([^\"]*)\".*")
            val nonceLine = lines.find { it.contains("nonce") }
            val nonceb64 = nonceRegex.find(nonceLine!!)!!.groups[1]!!.value
            val nonce = Base64.decode(nonceb64, Base64.DEFAULT)

            val rand = nonce.take(16)
            val autn = nonce.drop(16).take(16)
            val mac = nonce.drop(32)

            val challengeBytes =
                listOf(rand.size.toByte()) + rand + autn.size.toByte() + autn
            val challengeArray = challengeBytes.toByteArray()


            val challenge = Base64.encodeToString(challengeArray, Base64.NO_WRAP)
            Log.d("PHH", "Challenge B64 is $challenge")

            updateStatus("Requesting AKA challenge")
            val responseB64 = tm.getIccAuthentication(
                TelephonyManager.APPTYPE_USIM,
                TelephonyManager.AUTHTYPE_EAP_AKA,
                challenge
            )
            val response = Base64.decode(responseB64, Base64.DEFAULT)
            if (response[0] != (0xdb).toByte()) {
                updateStatus("AKA challenge failed")
                Log.d("PHH", "AKA challenge from SIP failed")
                throw Exception("AKA Challenge from SIP failed")
            }

            val responseStream = response.iterator()

            // 0xdb
            responseStream.nextByte()

            val resLen = responseStream.nextByte().toInt()
            Log.d("PHH", "resLen $resLen")
            val res = (0 until resLen).map { responseStream.nextByte() }.toList()

            val ckLen = responseStream.nextByte().toInt()
            Log.d("PHH", "ckLen $ckLen")
            val ck = (0 until ckLen).map { responseStream.nextByte() }.toList()

            val ikLen = responseStream.nextByte().toInt()
            Log.d("PHH", "ikLen $ikLen")
            val ik = (0 until ikLen).map { responseStream.nextByte() }.toList()

            Log.d("PHH", "Got res $res ck $ck ik $ik")
            val ikStr = ik.map { String.format("%02x", it) }.joinToString("")
            val ckStr = ck.map { String.format("%02x", it) }.joinToString("")
            val resStr = res.map { String.format("%02x", it) }.joinToString("")

            val portSRegex = Regex(".*port-s=([0-9]+).*")
            val portS = lines.map { portSRegex.find(it) }
                .find { it != null }!!.groupValues[1].toInt()
            val spiSRegex = Regex(".*spi-s=([0-9]+).*")
            val spiS = lines.map { spiSRegex.find(it) }
                .find { it != null }!!.groupValues[1].toUInt().toInt()
            val serverSPI =
                ipsecManager.allocateSecurityParameterIndex(pcscf!!, spiS)

            val portCRegex = Regex(".*port-c=([0-9]+).*")
            val portC = lines.map { portCRegex.find(it) }
                .find { it != null }!!.groupValues[1].toInt()
            val spiCRegex = Regex(".*spi-c=([0-9]+).*")
            val spiC = lines.map { spiCRegex.find(it) }
                .find { it != null }!!.groupValues[1].toUInt().toInt()
            val serverSPIC =
                ipsecManager.allocateSecurityParameterIndex(pcscf!!, spiC)

            val k =
                "\"IPv6\",\"$myAddr\",\"${pcscf!!.hostAddress}\",\"${mySPI1.spi}\",\"AES-CBC [RFC3602]\",\"0x$ckStr\",\"HMAC-SHA-1-96 [RFC2404]\",\"0x$ikStr\""
            val k2 =
                "\"IPv6\",\"${pcscf!!.hostAddress}\",\"$myAddr\",\"${spiS}\",\"AES-CBC [RFC3602]\",\"0x$ckStr\",\"HMAC-SHA-1-96 [RFC2404]\",\"0x$ikStr\""
            Log.d("PHH", k)
            Log.d("PHH", k2)

            val outgoingTransform = IpSecTransform.Builder(this)
                .setAuthentication(
                    IpSecAlgorithm(
                        IpSecAlgorithm.AUTH_HMAC_SHA1,
                        ik.toByteArray(),
                        96
                    )
                ).also {
                    if(securityServer.contains("ealg=aes")) {
                        it.setEncryption(
                            IpSecAlgorithm(
                                IpSecAlgorithm.CRYPT_AES_CBC,
                                ck.toByteArray()
                            ))
                    }
                }
                .buildTransportModeTransform(lp.linkAddresses[0].address, serverSPI)

            val ingoingTransform = IpSecTransform.Builder(this)
                .setAuthentication(
                    IpSecAlgorithm(
                        IpSecAlgorithm.AUTH_HMAC_SHA1,
                        ik.toByteArray(),
                        96
                    )
                ).also {
                    if(securityServer.contains("ealg=aes")) {
                        it.setEncryption(
                            IpSecAlgorithm(
                                IpSecAlgorithm.CRYPT_AES_CBC,
                                ck.toByteArray()
                            ))
                    }
                }
                .buildTransportModeTransform(pcscf!!, mySPI1)


            ipsecManager.applyTransportModeTransform(
                socketInIpsec,
                IpSecManager.DIRECTION_IN,
                ingoingTransform
            )
            ipsecManager.applyTransportModeTransform(
                socketInIpsec,
                IpSecManager.DIRECTION_OUT,
                outgoingTransform
            )

            //IPSec for server
            if(true) {
                val outgoingTransformC = IpSecTransform.Builder(this)
                    .setAuthentication(
                        IpSecAlgorithm(
                            IpSecAlgorithm.AUTH_HMAC_SHA1,
                            ik.toByteArray(),
                            96
                        )
                    ).also {
                        if(securityServer.contains("ealg=aes")) {
                            it.setEncryption(
                                IpSecAlgorithm(
                                    IpSecAlgorithm.CRYPT_AES_CBC,
                                    ck.toByteArray()
                                ))
                        }
                    }
                    .buildTransportModeTransform(lp.linkAddresses[0].address, serverSPIC)

                val ingoingTransformC = IpSecTransform.Builder(this)
                    .setAuthentication(
                        IpSecAlgorithm(
                            IpSecAlgorithm.AUTH_HMAC_SHA1,
                            ik.toByteArray(),
                            96
                        )
                    ).also {
                        if(securityServer.contains("ealg=aes"))
                            it.setEncryption(
                                IpSecAlgorithm(
                                    IpSecAlgorithm.CRYPT_AES_CBC,
                                    ck.toByteArray()
                                ))
                    }
                    .buildTransportModeTransform(pcscf!!, mySPI2)


                ipsecManager.applyTransportModeTransform(
                    serverSocketFd,
                    IpSecManager.DIRECTION_IN,
                    ingoingTransformC
                )
                ipsecManager.applyTransportModeTransform(
                    serverSocketFd,
                    IpSecManager.DIRECTION_OUT,
                    outgoingTransformC
                )
                thread {
                    while(true) {
                        val client = serverSocket.accept()
                        thread {
                            Log.d("PHH", "Got new client!")
                            val input = client.getInputStream()
                            //val reader = input.bufferedReader()
                            val reader = input.buffered()
                            val lines = mutableListOf<String>()
                            val output = client.getOutputStream()
                            lines.clear()
                            var contentLength = 0
                            for (line in reader.lines()) {
                                Log.d("PHH", "Client sent $line")
                                lines.add(line.trim())
                                if(line.trim() == "") break
                                if(line.contains("Content-Length"))
                                    contentLength = extractValue(line)!!.toInt()
                            }

                            val cseq = lines.find{ it.contains("CSeq")} ?: ""

                            val fwdLine = lines.find { it.contains("Max-Forwards") }
                            val fwd = (if(fwdLine != null) extractValue(fwdLine) else null)?.toInt() ?: 70

                            val toLine = lines.find { it.contains("To") }
                            val fromLine = lines.find { it.contains("From") }
                            val viaLine = lines.find { it.contains("Via") }

                            updateStatus("Unsolicited ${lines[0]}")

                            val dataArr = ByteArray(contentLength)
                            val nRead = reader.read(dataArr)

                            if(lines[0].contains("MESSAGE")) {
                                Log.d("PHH", "Client read $nRead ${dataArr.toList()}")
                                try {
                                    parseSms(dataArr)
                                } catch(t : Throwable) {
                                    Log.d("PHH", "Failed parsing message", t)
                                }
                            } else {
                                val data = String(dataArr)
                                Log.d("PHH", "Client read $nRead $data")
                            }

                            val myToTag =
                                "a" + Random.Default.nextBytes(6).map { String.format("%02x", it) }
                                    .joinToString("")

                            val addToTag = if(toLine!!.contains("tag=")) "" else ";tag=$myToTag"

                            val answer200 = """
                                SIP/2.0 200 OK
                                $viaLine
                                $cseq
                                $toLine$addToTag
                                $fromLine
                                Max-Forwards: ${fwd-1}
                                Expires: 600000
                                Content-Length: 0
                            """.trimIndent()
                            val answer = answer200
                            Log.d("PHH", "Replying back with $answer")
                            output.write(answer.replace("\n", "\r\n").toByteArray())
                            output.write("\r\n".toByteArray())
                            output.write("\r\n".toByteArray())
                            client.close()
                        }
                    }
                }
            }

            updateStatus("Connecting to IPsec socket")
            socketInIpsec.connect(InetSocketAddress(pcscf!!, portS))
            updateStatus("Connected to IPsec socket")


            val ipsecWriter = socketInIpsec.getOutputStream()
            val ipsecReader = socketInIpsec.getInputStream().bufferedReader()

            // username:realm:akaresult but akaresult is raw bytes
            val H1prefix = "$user:$realm:"
            val H1 = MessageDigest.getInstance("MD5")
                .digest((H1prefix.toByteArray().toList() + res).toByteArray())
                .toHex()

            Log.d("PHH", "H1 = $H1")
            // Method : digest url value
            // URL in REGISTER is uri:$realm
            val H2str = "REGISTER:sip:$realm"
            val H2 = H2str.toMD5()
            Log.d("PHH", "H2 = $H2str $H2")

            val cnonce =
                Random.Default.nextBytes(8).map { String.format("%02x", it) }
                    .joinToString("")
            Log.d("PHH", "cnonce $cnonce")
            val nonceCount = "00000001"
            // H1:nonce:nonceCount:clientNonce:qop:H2
            val challStr = "$H1:$nonceb64:$nonceCount:$cnonce:auth:$H2"
            val chall = challStr.toMD5()
            Log.d("PHH", "chall $challStr $chall")
            Log.d("PHH", "opaque $opaque")

            tag =
                "a" + Random.Default.nextBytes(6).map { String.format("%02x", it) }
                    .joinToString("")
            branch = "z9hG4bK_" + Random.Default.nextBytes(6)
                .map { String.format("%02x", it) }.joinToString("")
            callId =
                "a" + Random.Default.nextBytes(6).map { String.format("%02x", it) }
                    .joinToString("")

            val opaqueAdd = if (opaque != null) ",opaque=$opaque" else ""

            //Contact +sip.instance="<urn:gsma:imei:86687905-321566-0>";
            val msg2 = """
                            REGISTER sip:ims.mnc$mnc.mcc$mcc.3gppnetwork.org SIP/2.0
                            Via: SIP/2.0/TCP [$myAddr2]:${socketInIpsec.localPort};branch=$branch;rport
                            From: <sip:$user>;tag=$tag
                            To: <sip:$user>
                            Call-ID: $callId
                            Max-Forwards: 70
                            Expires: 600000
                            User-Agent: Xiaomi__Android_12_MIUI220114
                            Contact: <sip:$imsi@[$myAddr2]:${socketInIpsec.localPort};transport=tcp>;expires=600000;+sip.instance="<urn:gsma:imei:$imeiStr>";+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel";+g.3gpp.smsip;audio
                            Supported: path, gruu, sec-agree
                            Allow: INVITE, ACK, CANCEL, BYE, UPDATE, REFER, NOTIFY, MESSAGE, PRACK, OPTIONS
                            Authorization: Digest username="$user",realm="$realm",nonce="$nonceb64",uri="sip:$realm",response="$chall",algorithm=AKAv1-MD5,cnonce="$cnonce",qop=auth,nc=$nonceCount$opaqueAdd
                            Require: sec-agree
                            Proxy-Require: sec-agree
                            $secClient
                            Security-Verify: $securityServer
                            CSeq: 2 REGISTER
                            Content-Length: 0
                        """.trimIndent()

            updateStatus("Sending register 2")
            Log.d("PHH", "Sending $msg2")
            ipsecWriter.write(msg2.replace("\n", "\r\n").toByteArray())
            ipsecWriter.write("\r\n".toByteArray())
            ipsecWriter.write("\r\n".toByteArray())

            var myPhoneNumber = ""
            var mySip = ""
            var svcRoute = mutableListOf<String>()
            var path = ""
            lines.clear()
            for (line in ipsecReader.lines()) {
                lines.add(line.trim())
                Log.d("PHH", "IPSEC Received < $line")
                if (line.toLowerCase().startsWith("p-associated-uri")) {
                    val uri = extractValue(line)
                    if (uri!!.contains("tel:")) {
                        val phoneNumberRegex = Regex("<tel:([0-9+-]+)>")
                        myPhoneNumber = phoneNumberRegex.find(uri)!!.groupValues[1]
                    } else if (uri!!.contains("sip:")) {
                        val sipRegex = Regex("<sip:([^>]+)>")
                        mySip = sipRegex.find(uri)!!.groupValues[1]
                    }
                }
                if (line.startsWith("Service-Route")) {
                    svcRoute.add(extractValue(line)!!)
                }
                if (line.startsWith("Path"))
                    path = extractValue(line)!!

                if (line.trim() == "") break
            }
            updateStatus("Received register 2 answer ${lines[0]}, phone $myPhoneNumber")

            val route = (listOf(path) + svcRoute).joinToString(", ")
            Log.d("PHH", "Got my sip = $mySip, my number = $myPhoneNumber")

            //contact: ;+sip.instance="<urn:gsma:imei:86687905-321566-0>"
            val msg3 = """
                            SUBSCRIBE sip:$mySip SIP/2.0
                            Via: SIP/2.0/TCP [$myAddr2]:${socketInIpsec.localPort};branch=$branch;rport
                            P-Preferred-Identity: <sip:$mySip>
                            From: <sip:$mySip>;tag=$tag
                            To: <sip:$mySip>
                            Call-ID: $callId
                            Event: reg
                            Max-Forwards: 70
                            Expires: 600000
                            Route: $route
                            User-Agent: XXiaomi__Android_12_MIUI220208
                            Contact: <sip:$imsi@[$myAddr2]:${socketInIpsec.localPort};transport=tcp>;expires=600000;+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel";+g.3gpp.smsip;audio
                            Supported: path, gruu, sec-agree
                            Allow: INVITE, ACK, CANCEL, BYE, UPDATE, REFER, NOTIFY, MESSAGE, PRACK, OPTIONS
                            Authorization: Digest username="$user",realm="$realm",nonce="$nonceb64",uri="sip:$realm",response="$chall",algorithm=AKAv1-MD5,cnonce="$cnonce",qop=auth,nc=$nonceCount$opaqueAdd
                            Require: sec-agree
                            Proxy-Require: sec-agree
                            $secClient
                            Security-Verify: $securityServer
                            CSeq: 3 SUBSCRIBE
                            Content-Length: 0
                        """.trimIndent()

            Log.d("PHH", "Sending $msg3")

            updateStatus("Subscribing...")
            ipsecWriter.write(msg3.replace("\n", "\r\n").toByteArray())
            ipsecWriter.write("\r\n".toByteArray())
            ipsecWriter.write("\r\n".toByteArray())

            lines.clear()
            for (line in ipsecReader.lines()) {
                lines.add(line.trim())
                Log.d("PHH", "IPSEC Received < $line")
                if (line.trim() == "") break
            }

            updateStatus("Subscribe returned ${lines[0]}")

            Log.d("PHH", "End of susbcribe answer")
            
            if(true) {
                // TODO targetphonenumber
                val targetPhoneNumber = ""

                val sms = encodeSms(smsc, targetPhoneNumber, "not hello")
                val msg4 = """
                MESSAGE sip:+$smsc@$realm SIP/2.0
                Via: SIP/2.0/TCP [$myAddr2]:${socketInIpsec.localPort};branch=$branch;rport
                From: <sip:$mySip>;tag=$tag
                Max-Forwards: 70
                Expires: 600000
                To: <sip:+$smsc@$realm>
                Content-Type: application/vnd.3gpp.sms
                Call-ID: $callId
                CSeq: 4 MESSAGE
                User-Agent: Xiaomi__Android_12_MIUI220208
                Security-Verify: $securityServer
                P-Preferred-Identity: <sip:$mySip>
                Route: $route
                Allow: INVITE, ACK, CANCEL, BYE, UPDATE, REFER, NOTIFY, MESSAGE, PRACK, OPTIONS
                P-Asserted-Identity: <sip:$mySip>
                Content-Length: ${sms!!.size}
                """.trimIndent()

                updateStatus("Sending SMS")
                Log.d("PHH", "Sending $msg4")

                ipsecWriter.write(msg4.replace("\n", "\r\n").toByteArray())
                ipsecWriter.write("\r\n".toByteArray())
                ipsecWriter.write("\r\n".toByteArray())
                ipsecWriter.write(sms)

                lines.clear()
                for (line in ipsecReader.lines()) {
                    lines.add(line.trim())
                    Log.d("PHH", "IPSEC Received < $line")
                    if (line.trim() == "") break
                }
                updateStatus("SMS returned ${lines[0]}")
                Log.d("PHH", "End of send SMS return")
            }

            for (line in ipsecReader.lines()) {
                Log.d("PHH", "IPSEC Received < $line")
            }

            Log.d("PHH", "End of socket")

            socketInIpsec.close()
        } catch (e: Throwable) {
            Log.d("PHH", "Connecting SIP socket", e)
        }
    }



    @SuppressLint("HardwareIds", "MissingPermission")
    fun launchVolteNetwork() {
        val nm = getSystemService(ConnectivityManager::class.java)
        val tm = getSystemService(TelephonyManager::class.java)

        updateStatus("Requesting IMS network.")

        val sm = getSystemService(SubscriptionManager::class.java)
        val subscriptions = sm.activeSubscriptionInfoList

        if (subscriptions.size == 0) {
            updateStatus("No subscriptions available - end")
            return
        }

        val activeSubscription = subscriptions[0]
        val subId = activeSubscription.subscriptionId
        val imei = tm.getDeviceId(activeSubscription.simSlotIndex)

        val carrierName = activeSubscription.carrierName
        val displayName = activeSubscription.displayName
        val mcc = activeSubscription.mcc
        val mnc = activeSubscription.mnc
        val subscriptionInfoNumber = activeSubscription.number

        Log.d("PHH", "Found activeSubscription Id:" + subId + " displayName:" + displayName)

        nm.registerNetworkCallback(NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .setNetworkSpecifier(subId.toString())
            .addCapability(NetworkCapabilities.NET_CAPABILITY_IMS)
            //.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            //.addCapability(NetworkCapabilities.NET_CAPABILITY_XCAP)
            .build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    updateStatus("Got IMS network !!")
                    launchIms(network)
                }
            })
        Thread.sleep(120 * 1000L)
    }

    fun encodeSms(scAddress: String, destinationAddress: String, message: String): ByteArray? {
        val t = SmsMessage.getSubmitPdu(scAddress, destinationAddress, message, false)
        val pdu = t.encodedMessage
        val headerSize = 3
        val scSize = t.encodedScAddress?.size ?: 0
        val v = ByteArray(pdu.size + headerSize + scSize + 1)
        v[0] = 0
        v[1] = 0x22 // What is that?!? RP Message Reference
        v[2] = 0
        if(t.encodedScAddress != null) System.arraycopy(t.encodedScAddress, 0, v, 3, scSize)
        v[3 + scSize] = pdu.size.toByte()
        System.arraycopy(pdu, 0, v, 3 + scSize + 1, pdu.size)
        return v
    }

    fun parseSms(msg: ByteArray) {
        var currentMsg = msg.toList()
        val msgType = msg[0]
        if(msgType != 1.toByte()) return
        val msgRef = msg[1]
        currentMsg = currentMsg.drop(2)
        val originatorScLength = currentMsg[0].toInt() // Network to MS
        currentMsg = currentMsg.drop(1)

        val originatorSc = currentMsg.take(originatorScLength).toByteArray()
        currentMsg = currentMsg.drop(originatorScLength)


        val destinationLength = currentMsg[0].toInt()
        currentMsg = currentMsg.drop(1)

        val destination = currentMsg.take(destinationLength).toByteArray()
        currentMsg = currentMsg.drop(destinationLength)
        val pduSize = currentMsg[0]
        currentMsg = currentMsg.drop(1)

        //Prepend fake 0 for 0 scAddress ?!?
        val msg = SmsMessage.createFromPdu((listOf(0.toByte()) + currentMsg).toByteArray())
        Log.d("PHH", "Received SMS from ${msg.originatingAddress} also ${msg.displayOriginatingAddress} val ${msg.messageBody}")
        updateStatus("Received SMS from ${msg.displayOriginatingAddress} val ${msg.messageBody}")
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        /*thread {
            connectIke(this)
        }*/

        thread {
            launchVolteNetwork()
        }
    }
}
