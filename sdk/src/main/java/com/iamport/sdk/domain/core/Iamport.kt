package com.iamport.sdk.domain.core

import android.app.Application
import android.net.Uri
import android.util.Log
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.MainThread
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import com.iamport.sdk.BuildConfig
import com.iamport.sdk.data.sdk.*
import com.iamport.sdk.domain.service.ChaiService
import com.iamport.sdk.domain.utils.CONST
import com.iamport.sdk.domain.utils.Event
import com.iamport.sdk.domain.utils.PreventOverlapRun
import com.iamport.sdk.presentation.activity.IamportSdk
import com.iamport.sdk.presentation.contract.WebViewActivityContract
import com.orhanobut.logger.AndroidLogAdapter
import com.orhanobut.logger.Logger
import com.orhanobut.logger.Logger.d
import com.orhanobut.logger.Logger.i
import com.orhanobut.logger.PrettyFormatStrategy
import java.lang.ref.WeakReference


// TODO 이곳의 커플링을 최소한으로 줄이자
/**
 * 머천트와 소통하는 싱글턴 object 객체
 * 실제로는 IamportSdk 객체를 생성하여 동작함
 */
object Iamport {

    // sdk 객체
    private var iamportSdk: IamportSdk? = null // FIXME : 안에 웹뷰를 포함하여 나오는 워닝

    // 콜백
    private var impCallbackFunction: ((IamPortResponse?) -> Unit)? = null // 결제결과 callbck type 함수 호출
    private var approveCallback: ((IamPortApprove) -> Unit)? = null // 차이 결제 상태 approve 콜백

    // 웹뷰 액티비티 런처
    private var webViewActivityLauncher: ActivityResultLauncher<Payment>? = null
    private val webViewActivityContract = WebViewActivityContract()

    // 중복호출 방지 Utils
    private val preventOverlapRun by lazy { PreventOverlapRun() }
    private var isLogFormatBuild = false

    var response: IamPortResponse? = null // 내부의 imp_uid로 종료 콜백 중복호출 방지

    // Activity or Fragment 레벨에서 생성 했는지 확인
    private fun isSDKInit(payment: Payment): Boolean {
        if (iamportSdk == null) {
            val errMsg = "IAMPORT SDK was not Init. Please call Iamport.init() in your start code(ex: onAttach() or onCreate() or etc.. )"
            Log.e(CONST.IAMPORT_LOG, errMsg)
            callback(IamPortResponse.makeFail(payment, msg = errMsg))
            return false
        }
        return true
    }


    // ===========================================

    // 하위 호환
    @Deprecated(message = "이 함수는 아무 동작하지 않으며, 호출할 필요가 없습니다. (sdk version > 1.2.0)")
    fun create(app: Application) {
        // Do Nothing
    }

    // 하위 호환
    @Deprecated(message = "이 함수는 아무 동작하지 않으며, 호출할 필요가 없습니다. (sdk version > 1.2.0)")
    fun createWithKoin(app: Application) {
        // Do Nothing
    }

    private fun buildLogFormat() {

        val formatStrategy = PrettyFormatStrategy.newBuilder().apply {
            tag(CONST.IAMPORT_LOG)
            if (BuildConfig.DEBUG) {
                methodCount(3)
            } else {
                showThreadInfo(false)       // (Optional) Whether to show thread info or not. Default true
                    .methodCount(0)         // (Optional) How many method line to show. Default 2
                    .methodOffset(5)        // (Optional) Hides internal method calls up to offset. Default 5
            }
        }.build()

        Logger.addLogAdapter(object : AndroidLogAdapter(formatStrategy) {
            override fun isLoggable(priority: Int, tag: String?): Boolean {
                if (!BuildConfig.DEBUG && priority <= Logger.DEBUG) {
                    return false
                }
                return true
            }
        })

        isLogFormatBuild = true
        d("Log Format build for IAMPORT SDK")

//        v("LOG IAMPORT VERBOSE")
//        d("LOG IAMPORT DEBUG")
//        i("LOG IAMPORT INFO")
//        w("LOG IAMPORT WANRING")
//        e("LOG IAMPORT ERROR")
    }


    // ============================================

    /**
     * 외부에서 SDK 종료
     */
    @MainThread
    fun close() {
        iamportSdk?.close()
    }

    /**
     * 외부에서 SDK 실패 종료
     */
    @MainThread
    fun failFinish() {
        iamportSdk?.failFinish()
    }

    /**
     * 전달받은 결제결과 콜백
     */
    val callback = fun(iamPortResponse: IamPortResponse?) {

        if (iamPortResponse == null) {
            i("iamPortResponse 없이 결제 종료")
            impCallbackFunction?.invoke(iamPortResponse)
            return
        }

        val isCalledResponse = iamPortResponse.imp_uid?.let {
            isCalledResponse(it)
        }

        if (isCalledResponse == true) {
            return
        }

        response = iamPortResponse // 호출된적 없는 imp_uid이니 set

        impCallbackFunction?.invoke(iamPortResponse)
    }


    private fun isCalledResponse(impUid: String): Boolean {
        if (response?.imp_uid == impUid) {
            i("이미 종료 호출된 imp_uid[$impUid] 이므로 다시 호출하지 않음")
            return true
        }
        return false
    }


    /**
     * SDK Activity 열기 위한 Contract for Activity
     * @param componentActivity : Host Activity
     */
    fun init(componentActivity: ComponentActivity) {
        if (!isLogFormatBuild) {
            buildLogFormat()
        }

        d("INITIALIZE IAMPORT SDK from activity")

        preventOverlapRun.init()
        iamportSdk?.initClose()
        iamportSdk = null

        webViewActivityLauncher = componentActivity.registerForActivityResult(webViewActivityContract) {
            callback(it)
        }

        iamportSdk = IamportSdk(activity = WeakReference(componentActivity), webViewActivityLauncher = webViewActivityLauncher)
    }


    /**
     * SDK Activity 열기 위한 Contract for Fragment
     * @param fragment : Host Fragment
     */
    fun init(fragment: Fragment) {
        if (!isLogFormatBuild) {
            buildLogFormat()
        }

        d("INITIALIZE IAMPORT SDK from fragment")

        preventOverlapRun.init()
        iamportSdk?.initClose()
        iamportSdk = null

        webViewActivityLauncher = fragment.registerForActivityResult(webViewActivityContract) {
            callback(it)
        }

        iamportSdk = IamportSdk(fragment = WeakReference(fragment), webViewActivityLauncher = webViewActivityLauncher)
    }

    /**
     * 결제 요청
     * @param ((IamPortApprove?) -> Unit)? : (옵셔널) 차이 최종 결제 요청전 콜백
     * @param (IamPortResponse?) -> Unit: ICallbackPaymentResult? : 결제결과 callbck type#2 함수 호출
     */
    fun payment(
        userCode: String,
        tierCode: String? = null,
        webviewMode: WebView? = null,
        iamPortRequest: IamPortRequest,
        approveCallback: ((IamPortApprove) -> Unit)? = null,
        paymentResultCallback: (IamPortResponse?) -> Unit
    ) {

        val payment = Payment(userCode, tierCode = tierCode, iamPortRequest = iamPortRequest)
        if (!isSDKInit(payment)) {
            return
        }

        disableWebViewMode()
        if (webviewMode != null) {
            enableWebViewMode(webviewMode)
        }

        preventOverlapRun.launch {
            corePayment(payment, approveCallback, paymentResultCallback)
        }
    }

    /**
     * 결제 요청
     * @param ((IamPortApprove?) -> Unit)? : (옵셔널) 차이 최종 결제 요청전 콜백
     * @param (IamPortResponse?) -> Unit: ICallbackPaymentResult? : 결제결과 callbck type#2 함수 호출
     */
    fun certification(
        userCode: String,
        tierCode: String? = null,
        webviewMode: WebView? = null,
        iamPortCertification: IamPortCertification,
        resultCallback: (IamPortResponse?) -> Unit
    ) {
        val payment = Payment(userCode, tierCode = tierCode, iamPortCertification = iamPortCertification)
        if (!isSDKInit(payment)) {
            return
        }

        disableWebViewMode()
        if (webviewMode != null) {
            enableWebViewMode(webviewMode)
        }

        preventOverlapRun.launch {
            coreCertification(payment, resultCallback)
        }
    }

    internal fun coreCertification(
        payment: Payment,
        paymentResultCallback: ((IamPortResponse?) -> Unit)?
    ) {
        impCallbackFunction = paymentResultCallback
        iamportSdk?.initStart(payment, paymentResultCallback)
    }

    internal fun corePayment(
        payment: Payment,
        approveCallback: ((IamPortApprove) -> Unit)?,
        paymentResultCallback: ((IamPortResponse?) -> Unit)?
    ) {
        this.approveCallback = approveCallback
        impCallbackFunction = paymentResultCallback
        iamportSdk?.initStart(payment, approveCallback, paymentResultCallback)
    }

    // ======================================================
    // 웹뷰 모드 관련 인터페이스

    // webview 사용 모드
    private fun enableWebViewMode(webview: WebView) {
        d("enableWebViewMode $webview")
        iamportSdk?.enableWebViewMode(WeakReference(webview))
    }

    // webview 사용 모드 해제
    private fun disableWebViewMode() {
        iamportSdk?.disableWebViewMode()
    }

    // webview 모드여부 확인
    fun isWebViewMode(): Boolean {
        return iamportSdk?.isWebViewMode() ?: false
    }

    // ======================================================
    // mobile web standalone 사용 모드
    fun pluginMobileWebSupporter(webview: WebView) {
        iamportSdk?.pluginMobileWebSupporter(WeakReference(webview))
    }

    /**
     * MobileWebMode 일 때, 웹뷰의 url 이 변경되면 값이 전달됨
     */
    fun mobileWebModeShouldOverrideUrlLoading(): LiveData<Event<Uri>>? {
        return iamportSdk?.mobileWebModeShouldOverrideUrlLoading()
    }
    // ======================================================


    // ======================================================
    // 차이 관련 인터페이스

    // 차이 실행중 포그라운드 서비스 실행 여부
    fun enableChaiPollingForegroundService(enableService: Boolean, enableFailStopButton: Boolean = false) {
        ChaiService.enableForegroundService = enableService
        ChaiService.enableForegroundServiceStopButton = enableFailStopButton
    }

    // 현재 차이 폴링 여부
    fun isPolling(): LiveData<Event<Boolean>>? {
        return iamportSdk?.isPolling()
    }

    // 현재 차이 폴링 여부에 대한 값
    fun isPollingValue(): Boolean {
        return isPolling()?.value?.peekContent() ?: false
    }

    /**
     * 외부에서 차이 최종결제 요청
     */
    fun approvePayment(approve: IamPortApprove) {
        iamportSdk?.requestApprovePayments(approve)
    }
    // ======================================================
}
