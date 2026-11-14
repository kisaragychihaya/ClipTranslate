/*
 * Copyright (c) 2012-2014,2016,2017,2019-2022,2023 Qualcomm Technologies, Inc.
 * All Rights Reserved.
 * Confidential and Proprietary - Qualcomm Technologies, Inc.
 */
#ifndef REMOTE_H
#define REMOTE_H

#include <stdint.h>
#include <sys/types.h>
#include "remote_work.h"

#ifdef __cplusplus
extern "C" {
#endif

#ifndef __QAIC_REMOTE
#define __QAIC_REMOTE(ff) ff
#endif ///__QAIC_REMOTE

#ifndef __QAIC_REMOTE_EXPORT
#ifdef _WIN32
#ifdef _USRDLL
#define __QAIC_REMOTE_EXPORT    __declspec(dllexport)
#elif defined(STATIC_LIB)
#define __QAIC_REMOTE_EXPORT    /** Define for static libk */
#else   ///STATIC_LIB
#define __QAIC_REMOTE_EXPORT    __declspec(dllimport)
#endif ///_USRDLL
#else
#define __QAIC_REMOTE_EXPORT
#endif ///_WIN32
#endif ///__QAIC_REMOTE_EXPORT

#ifndef __QAIC_RETURN
#ifdef _WIN32
#define __QAIC_RETURN _Success_(return == 0)
#else
#define __QAIC_RETURN
#endif ///_WIN32
#endif ///__QAIC_RETURN

#ifndef __QAIC_IN
#ifdef _WIN32
#define __QAIC_IN _In_
#else
#define __QAIC_IN
#endif ///_WIN32
#endif ///__QAIC_IN

#ifndef __QAIC_IN_CHAR
#ifdef _WIN32
#define __QAIC_IN_CHAR _In_z_
#else
#define __QAIC_IN_CHAR
#endif ///_WIN32
#endif ///__QAIC_IN_CHAR

#ifndef __QAIC_IN_LEN
#ifdef _WIN32
#define __QAIC_IN_LEN(len) _Inout_updates_bytes_all_(len)
#else
#define __QAIC_IN_LEN(len)
#endif ///_WIN32
#endif ///__QAIC_IN_LEN

#ifndef __QAIC_OUT
#ifdef _WIN32
#define __QAIC_OUT _Out_
#else
#define __QAIC_OUT
#endif ///_WIN32
#endif ///__QAIC_OUT

#ifndef __QAIC_INT64PTR
#ifdef _WIN32
#define __QAIC_INT64PTR uintptr_t
#else
#define __QAIC_INT64PTR uint64_t
#endif ///_WIN32
#endif ///__QAIC_INT64PTR

#ifndef __QAIC_REMOTE_ATTRIBUTE
#define __QAIC_REMOTE_ATTRIBUTE
#endif ///__QAIC_REMOTE_ATTRIBUTE

/** Retrieves method attribute from the scalars parameter */
#define REMOTE_SCALARS_METHOD_ATTR(dwScalars)   (((dwScalars) >> 29) & 0x7)

/** Retrieves method index from the scalars parameter */
#define REMOTE_SCALARS_METHOD(dwScalars)        (((dwScalars) >> 24) & 0x1f)

/** Retrieves number of input buffers from the scalars parameter */
#define REMOTE_SCALARS_INBUFS(dwScalars)        (((dwScalars) >> 16) & 0x0ff)

/** Retrieves number of output buffers from the scalars parameter */
#define REMOTE_SCALARS_OUTBUFS(dwScalars)       (((dwScalars) >> 8) & 0x0ff)

/** Retrieves number of input handles from the scalars parameter */
#define REMOTE_SCALARS_INHANDLES(dwScalars)     (((dwScalars) >> 4) & 0x0f)

/** Retrieves number of output handles from the scalars parameter */
#define REMOTE_SCALARS_OUTHANDLES(dwScalars)    ((dwScalars) & 0x0f)

/** Makes the scalar using the method attr, index and number of io buffers and handles */
#define REMOTE_SCALARS_MAKEX(nAttr,nMethod,nIn,nOut,noIn,noOut) \
          ((((uint32_t)   (nAttr) &  0x7) << 29) | \
           (((uint32_t) (nMethod) & 0x1f) << 24) | \
           (((uint32_t)     (nIn) & 0xff) << 16) | \
           (((uint32_t)    (nOut) & 0xff) <<  8) | \
           (((uint32_t)    (noIn) & 0x0f) <<  4) | \
            ((uint32_t)   (noOut) & 0x0f))

#define REMOTE_SCALARS_MAKE(nMethod,nIn,nOut) REMOTE_SCALARS_MAKEX(0,nMethod,nIn,nOut,0,0)

/** Retrieves number of io buffers and handles */
#define REMOTE_SCALARS_LENGTH(sc) (REMOTE_SCALARS_INBUFS(sc) +\
                                   REMOTE_SCALARS_OUTBUFS(sc) +\
                                   REMOTE_SCALARS_INHANDLES(sc) +\
                                   REMOTE_SCALARS_OUTHANDLES(sc))

/** Defines the domain IDs for supported DSPs */
#define ADSP_DOMAIN_ID    0
#define MDSP_DOMAIN_ID    1
#define SDSP_DOMAIN_ID    2
#define CDSP_DOMAIN_ID    3
#define CDSP1_DOMAIN_ID   4

/** Defines the domain names for supported DSPs*/
#define ADSP_DOMAIN_NAME "adsp"
#define MDSP_DOMAIN_NAME "mdsp"
#define SDSP_DOMAIN_NAME "sdsp"
#define CDSP_DOMAIN_NAME "cdsp"
#define CDSP1_DOMAIN_NAME "cdsp1"
#define GDSP0_DOMAIN_NAME "gdsp0"
#define GDSP1_DOMAIN_NAME "gdsp1"

/** Defines to prepare URI for multi-domain calls */
#define ADSP_DOMAIN "&_dom=adsp"
#define MDSP_DOMAIN "&_dom=mdsp"
#define SDSP_DOMAIN "&_dom=sdsp"
#define CDSP_DOMAIN "&_dom=cdsp"
#define CDSP1_DOMAIN "&_dom=cdsp1"
#define GDSP0_DOMAIN "&_dom=gdsp0"
#define GDSP1_DOMAIN "&_dom=gdsp1"

/** Internal transport prefix */
#define ITRANSPORT_PREFIX "'\":;./\\"

/** Maximum length of URI for remote_handle_open() calls */
#define MAX_DOMAIN_URI_SIZE 12

/**
 * URI query token used to attach the caller's Application ID (AppID).
 *
 * AppID is a numeric identifier that represents the originating client
 * application that initiated a FastRPC request.
 *
 * This is especially important when FastRPC calls are made via shared
 * or proxy processes, where the visible UID may not correspond to the
 * actual calling application.
 *
 * The AppID value must be appended in decimal format after this token
 * when constructing the URI string.
 *
 *   Example: "&_appid=12345"
 */
#define FASTRPC_URI_APPID_TOKEN "&_appid="

/** Token to specify the priority of a handle */
#define FASTRPC_URI_PRIORITY_TOKEN "&_hpriority="

/** Macro to generate token string for priority */
#define FASTRPC_HANDLE_PRIORITY_LEVEL(priority)  \
                FASTRPC_URI_PRIORITY_TOKEN #priority

/**
 * The following defines are used to specify the priority level of a handle.
 * Lower numbers indicate higher priority.
 *
 * If no priority level is specified, then handles are opened at a default
 * priority having the same thread-priority ceiling of the session.
 */
/**
 * @deprecated This macro is deprecated. Use
 * nErr = remote_get_info(domain, HANDLE_PRIORITY_SUPPORT, &capability);
 * to get the MIN number of thread groups.
 */
#define FASTRPC_HANDLE_PRIORITY_MIN 6
#define FASTRPC_HANDLE_PRIORITY_MAX 1

/**
 * Max length of DMA buffer name
 * As per the restriction imposed by the DMA heap library.
 */
#define RPCMEM_MAX_BUF_NAME_LEN 32

/** Domain type for multi-domain RPC calls */
typedef struct domain_t {
    /** Domain ID */
    int id;
    /** URI for remote_handle_open */
    char uri[MAX_DOMAIN_URI_SIZE];
} domain;

/** Remote handle parameter for RPC calls */
typedef uint32_t remote_handle;

/** Remote handle parameter for multi-domain RPC calls */
typedef uint64_t remote_handle64;

/** 32-bit Remote buffer parameter for RPC calls */
typedef struct {
    /** Address of a remote buffer */
    void *pv;
    /** Size of a remote buffer */
    size_t nLen;
} remote_buf;

/** 64-bit Remote buffer parameter for RPC calls */
typedef struct {
    /** Address of a remote buffer */
    uint64_t pv;
    /** Size of a remote buffer */
    int64_t nLen;
} remote_buf64;

/** 32-bit Remote DMA handle parameter for RPC calls */
typedef struct {
    /** File descriptor of a remote buffer */
    int32_t fd;
    /** Offset of the file descriptor */
    uint32_t offset;
} remote_dma_handle;

/** 64-bit Remote DMA handle parameter for RPC calls */
typedef struct {
    /** File descriptor of a remote buffer */
    int32_t fd;
    /** Offset of the file descriptor */
    uint32_t offset;
    /** Size of buffer */
    uint32_t len;
} remote_dma_handle64;

/** 32-bit Remote Arg structure for RPC calls */
typedef union {
    /** 32-bit remote buffer */
    remote_buf buf;
    /** non-domains remote handle */
    remote_handle h;
    /** multi-domains remote handle */
    remote_handle64 h64;
    /** 32-bit remote dma handle */
    remote_dma_handle dma;
} remote_arg;

/** 64-bit Remote Arg structure for RPC calls */
typedef union {
    /** 64-bit remote buffer */
    remote_buf64 buf;
    /** non-domains remote handle */
    remote_handle h;
    /** multi-domains remote handle */
    remote_handle64 h64;
    /** 64-bit remote dma handle */
    remote_dma_handle64 dma;
} remote_arg64;

/** Async response type */
enum fastrpc_async_notify_type {
    /** No notification required */
    FASTRPC_ASYNC_NO_SYNC,

    /** Callback notification using fastrpc_async_callback */
    FASTRPC_ASYNC_CALLBACK,

    /** User will poll for the notification */
    FASTRPC_ASYNC_POLL,

    /** Always keep as the last member */
    FASTRPC_ASYNC_TYPE_MAX,
};

/** Job id of Async job queued to DSP */
typedef uint64_t fastrpc_async_jobid;

/** Async call back response type, input structure */
typedef struct fastrpc_async_callback {
    /** Callback function for async notification */
    void (*fn)(fastrpc_async_jobid jobid, void* context, int result);
    /** Current context to identify the callback */
    void *context;
}fastrpc_async_callback_t;

/** Async descriptor to submit async job */
typedef struct fastrpc_async_descriptor {
    /** Async response type */
    enum fastrpc_async_notify_type type;
    /** Job id of Async job queued to DSP */
    fastrpc_async_jobid jobid;
    /** Async call back response type */
    fastrpc_async_callback_t cb;
}fastrpc_async_descriptor_t;


/**
 * Flags used in struct remote_rpc_control_latency
 * for request ID DSPRPC_CONTROL_LATENCY
 * in remote handle control interface
 **/
enum remote_rpc_latency_flags {

    /** Request ID to disable QOS */
    RPC_DISABLE_QOS,

    /** Control cpu low power modes based on RPC activity in 100 ms window.
    * Recommended for latency sensitive use cases.
    */
    RPC_PM_QOS,

    /** DSP driver predicts completion time of a method and send CPU wake up signal to reduce wake up latency.
     * Recommended for moderate latency sensitive use cases. It is more power efficient compared to pm_qos control.
     */
    RPC_ADAPTIVE_QOS,

    /**
     * After sending invocation to DSP, CPU will enter polling mode instead of
    * waiting for a glink response. This will boost fastrpc performance by
    * reducing the CPU wakeup and scheduling times. Enabled only for sync RPC
    * calls. Using this option also enables PM QoS with a latency of 100 us.
    */
    RPC_POLL_QOS,
};

/**
 * Structure used for request ID `DSPRPC_CONTROL_LATENCY`
 * in remote handle control interface
 **/
struct remote_rpc_control_latency {
/** Enable latency optimization techniques to meet requested latency. Use remote_rpc_latency_flags */
    uint32_t enable;

/**
 * Latency in microseconds.
 *
 * When used with RPC_PM_QOS or RPC_ADAPTIVE_QOS, user should pass maximum RPC
 * latency that can be tolerated. It is not guaranteed that fastrpc will meet
 * this requirement. 0 us latency is ignored. Recommended value is 100.
 *
 * When used with RPC_POLL_QOS, user needs to pass the expected execution time
 * of method on DSP. CPU will poll for a DSP response for that specified duration
 * after which it will timeout and fall back to waiting for a glink response.
 * Max value that can be passed is 10000 (10 ms)
 */
    uint32_t latency;
};

/**
 * @enum dsp_image_config
 * @brief Type of DSP image that can be queried as a capability using the
 * `DSP_IMAGE_CONFIG` attribute with `remote_handle_control` api with
 * `DSPRPC_GET_DSP_INFO` request id.
 */
enum dsp_image_config {
	/** Compute image (eg: NSP) */
	FASTRPC_DSP_COMPUTE = 1,

	/** Compute-lite image (eg: HPASS with NSP image) */
	FASTRPC_DSP_COMPUTE_LITE,

	/** Audio image (eg: LPASS / HPASS with audio image) */
	FASTRPC_DSP_AUDIO,
};

/**
 * @struct fastrpc_capability
 * @brief Argument to query DSP capability with request ID DSPRPC_GET_DSP_INFO
 */
typedef struct remote_dsp_capability {
    /** @param[in] : DSP domain ADSP_DOMAIN_ID, SDSP_DOMAIN_ID, or CDSP_DOMAIN_ID */
    uint32_t domain;
    /** @param[in] : One of the DSP/kernel attributes from enum remote_dsp_attributes */
    uint32_t attribute_ID;
    /** @param[out] : Result of the DSP/kernel capability query based on attribute_ID */
    uint32_t capability;
}fastrpc_capability;


/**
 * @enum remote_dsp_attributes
 * @brief Different types of DSP capabilities queried via remote_handle_control
 * using DSPRPC_GET_DSP_INFO request id.
 * DSPRPC_GET_DSP_INFO should only be used with remote_handle_control() as a handle
 * is not needed to query DSP capabilities.
 * To query DSP capabilities fill out 'domain' and 'attribute_ID' from structure
 * remote_dsp_capability. DSP capability will be returned on variable 'capability'.
 */
enum remote_dsp_attributes {
    /** Check if DSP supported: supported = 1,
                                     unsupported = 0 */
    DOMAIN_SUPPORT,

    /** DSP unsigned PD support: supported = 1,
                                     unsupported = 0 */
    UNSIGNED_PD_SUPPORT,

    /** Number of HVX 64B support */
    HVX_SUPPORT_64B,

    /** Number of HVX 128B support */
    HVX_SUPPORT_128B,

    /** Max page size allocation possible in VTCM */
    VTCM_PAGE,

    /** Number of page_size blocks available */
    VTCM_COUNT,

    /** Hexagon processor architecture version */
    ARCH_VER,

    /** HMX Support Depth */
    HMX_SUPPORT_DEPTH,

    /** HMX Support Spatial */
    HMX_SUPPORT_SPATIAL,

    /** Async FastRPC Support */
    ASYNC_FASTRPC_SUPPORT,

    /** DSP User PD status notification Support */
    STATUS_NOTIFICATION_SUPPORT ,

    /** Multicast widget programming */
    MCID_MULTICAST,

    /** Mapping in extended address space on DSP */
    EXTENDED_MAP_SUPPORT,

    /** DSP support for handle priority */
    HANDLE_PRIORITY_SUPPORT,

    /**
      * Configuration of image flashed on DSP.
      * Will be one of `enum dsp_image_config`
      */
    DSP_IMAGE_CONFIG,

    /** DSPQueue support
      * Expected value:
      *     1, if it's supported,
      *     0, otherwise.
      */
    DSPQUEUE_SUPPORT,

    /** Read only import buffer supported
      * Expected value:
      *     1, if it's supported,
      *     0, otherwise.
      */
    READONLY_IMPORT_BUFFER_SUPPORT,

    /** Query total memory available on the other chip's DRAM.
      * Pass domain id of any of the DSPs on the other chip as input in
      * `fastrpc_capability` struct.
      * Size will be returned in KB (kilobytes).
      */
    TOTAL_DISCRETE_GLOBAL_MEM,

    /** Query local memory of specific domain on the other chip's DRAM.
      * Pass domain id of that specific DSP as input in `fastrpc_capability`
      * struct.
      * Size will be returned in KB (kilobytes).
      */
    TOTAL_DISCRETE_LOCAL_MEM,

    /** a remote_get_info query with this attribute returns maximum number of
        sessions supported per process per domain
        */
    MAX_SESSIONS_PER_PROCESS,

    /** Always keep as last member */
    FASTRPC_MAX_DSP_ATTRIBUTES,
};

/** Macro for backward compatibility. Clients can compile wakelock request code
 * in their app only when this is defined
 */
#define FASTRPC_WAKELOCK_CONTROL_SUPPORTED 1

/**
 * Structure used for request ID `DSPRPC_CONTROL_WAKELOCK`
 * in remote handle control interface
 **/
struct remote_rpc_control_wakelock {
    /** enable control of wake lock */
    uint32_t enable;
};

/**
 * Structure used for request ID `DSPRPC_GET_DOMAIN`
 * in remote handle control interface.
 * Get domain ID associated with an opened handle to remote interface of type remote_handle64.
 * remote_handle64_control() returns domain for a given handle
 * remote_handle_control() API returns default domain ID
 */
typedef struct remote_rpc_get_domain {
    /** @param[out] : domain ID associcated with handle */
    int domain;
} remote_rpc_get_domain_t;

/**
 * Structure used for request IDs `DSPRPC_SET_PATH` and
 * `DSPRPC_GET_PATH` in remote handle control interface.
 */
struct remote_control_custom_path {
    /** value size including NULL char */
    int32_t value_size;
    /** key used for storing the path */
    const char* path;
    /** value which will be used for file operations when the corresponding key is specified in the file URI */
    char* value;
};

/**
 * Request IDs for remote handle control interface
 **/
enum handle_control_req_id {
    /** Reserved */
    DSPRPC_RESERVED,

    /** Request ID to enable/disable QOS */
    DSPRPC_CONTROL_LATENCY ,

    /** Request ID to get dsp capabilites from kernel and Hexagon */
    DSPRPC_GET_DSP_INFO,

    /** Request ID to enable wakelock for the given domain */
    DSPRPC_CONTROL_WAKELOCK,

    /** Request ID to get the default domain or domain associated to an exisiting handle */
    DSPRPC_GET_DOMAIN,

    /** Request ID to add a custom path to the hash table */
    DSPRPC_SET_PATH,

    /** Request ID to read a custom path to the hash table */
    DSPRPC_GET_PATH,

    /** Request ids 7-14 reserved for internal use */
    DSPRPC_RESERVED_1 = 14,

    /** Add new request ids only from here */
};

/**
 * Structure used for request ID `FASTRPC_THREAD_PARAMS`
 * in remote session control interface
 **/
struct remote_rpc_thread_params {
    /** Remote subsystem domain ID, pass -1 to set params for all domains */
    int domain;
    /** User thread priority (1 to 255), pass -1 to use default */
    int prio;
    /** User thread stack size, pass -1 to use default */
    int stack_size;
};

/**
 * Structure used for request ID `DSPRPC_CONTROL_UNSIGNED_MODULE`
 * in remote session control interface
 **/
struct remote_rpc_control_unsigned_module {
    /** Remote subsystem domain ID, -1 to set params for all domains */
    int domain;
    /** Enable unsigned module loading */
    int enable;
};

/**
 * Structure used for request ID `FASTRPC_RELATIVE_THREAD_PRIORITY`
 * in remote session control interface
 **/
struct remote_rpc_relative_thread_priority {
    /** Remote subsystem domain ID, pass -1 to update priority for all domains */
    int domain;
    /** the value by which the default thread priority needs to increase/decrease
                                     * DSP thread priorities run from 1 to 255 with 1 being the highest thread priority.
                                     * So a negative relative thread priority value will 'increase' the thread priority,
                                     * a positive value will 'decrease' the thread priority.
                                     */
    int relative_thread_priority;
};

/**
 * When a remote invocation does not return,
 * then call "remote_session_control" with FASTRPC_REMOTE_PROCESS_KILL requestID
 * and the appropriate remote domain ID. Once remote process is successfully
 * killed, before attempting to create new session, user is expected to
 * close all open handles for shared objects in case of domains.
 * And, user is expected to unload all shared objects including
 * libcdsprpc.so/libadsprpc.so/libmdsprpc.so/libsdsprpc.so in case of non-domains.
 */
struct remote_rpc_process_clean_params {
    /** Domain ID  to recover process */
    int domain;
};

/**
 * Structure used for RPC process exception parameters
 * in remote session control interface.
 **/

struct remote_rpc_process_exception_with_param {
	/** @param[in]: Domain ID to recover process */
	int domain;

	/** @param[in]: Pointer to exception information string */
	char* exception_info;

	/** @param[in]: Length of the exception_info string including null
                    terminator */
	uint32_t exception_info_len;
};

/**
 * Structure used for request ID `FASTRPC_SESSION_CLOSE`
 * in remote session control interface
 **/
struct remote_rpc_session_close {
    /** Remote subsystem domain ID, -1 to close all handles for all domains */
    int domain;
};

/**
 * Structure used for request ID `FASTRPC_CONTROL_PD_DUMP`
 * in remote session control interface
 * This is used to enable/disable PD dump for userPDs on the DSP
 **/
struct remote_rpc_control_pd_dump {
    /** Remote subsystem domain ID, -1 to set params for all domains */
    int domain;
    /** Enable PD dump of user PD on the DSP */
    int enable;
};

/**
 * Structure used for request ID `FASTRPC_REMOTE_PROCESS_EXCEPTION`
 * in remote session control interface
 * This is used to trigger exception in the userPDs running on the DSP
 **/
typedef struct remote_rpc_process_clean_params remote_rpc_process_exception;

/**
 * Process types
 * Return values for FASTRPC_REMOTE_PROCESS_TYPE control req ID for remote_handle_control
 * Return values denote the type of process on remote subsystem
**/
enum fastrpc_process_type {
    /** Signed PD running on the DSP */
    PROCESS_TYPE_SIGNED,

    /** Unsigned PD running on the DSP */
    PROCESS_TYPE_UNSIGNED,

};

/**
 * Structure for remote_session_control,
 * used with FASTRPC_REMOTE_PROCESS_TYPE request ID
 * to query the type of PD running defined by enum fastrpc_process_type
 * @param[in] : Domain of process
 * @param[out] : Process_type belonging to enum fastrpc_process_type
 */
struct remote_process_type {
    /** @param[in] : Domain of process */
    int domain;
    /** @param[out] : Process_type belonging to enum fastrpc_process_type */
    int process_type;
};

/**
 * DSP user PD status notification flags
 * Status flags for the user PD on the DSP returned by the status notification function
 *
**/
typedef enum remote_rpc_status_flags {
    /** DSP user process is up */
    FASTRPC_USER_PD_UP,

    /** DSP user process exited */
    FASTRPC_USER_PD_EXIT,

    /** DSP user process forcefully killed. Happens when DSP resources needs to be freed. */
    FASTRPC_USER_PD_FORCE_KILL,

    /** Exception in the user process of DSP. */
    FASTRPC_USER_PD_EXCEPTION,

    /** Subsystem restart of the DSP, where user process is running. */
    FASTRPC_DSP_SSR,

    /* Reserved for fastrpc internal usage. */
    FASTRPC_INTERNAL_STATUS_RESERVED_1,

    /** RPC call timed out */
    FASTRPC_USERPD_TIMEOUT,

} remote_rpc_status_flags_t;

/**
 * fastrpc_notif_fn_t
 * Notification call back function
 *
 * @param context, context used in the registration
 * @param domain, domain of the user process
 * @param session, session id of user process
 * @param status, status of user process
 * @retval, 0 on success
 */
typedef int (*fastrpc_notif_fn_t)(void *context, int domain, int session, remote_rpc_status_flags_t status);


/**
 * Structure for remote_session_control,
 * used with FASTRPC_REGISTER_STATUS_NOTIFICATIONS request ID
 * to receive status notifications of the user PD on the DSP
 * To register for RPC call timeout notifications, use the v2
 * version of this structure.
**/
typedef struct remote_rpc_notif_register {
    /** @param[in] : Context of the client */
    void *context;
    /** @param[in] : DSP domain ADSP_DOMAIN_ID, SDSP_DOMAIN_ID, or CDSP_DOMAIN_ID */
    int domain;
    /** @param[in] : Notification function pointer */
    fastrpc_notif_fn_t notifier_fn;
} remote_rpc_notif_register_t;

/**
 * Struct to be used with FASTRPC_REGISTER_STATUS_NOTIFICATIONS request
 * id with remote_session_control().
 * User can register to receive a notification when any of their
 * rpc calls exceed specified timeout.
 * Returns :
 * AEE_SUCCESS : When timeout set successfully
 * AEE_EUNSUPPORTED : If unsupported domain-id passed
 * AEE_EBADPARM : In case of invalid datalen, domain-id or NULL param
**/
typedef struct remote_rpc_notif_register_v2 {
    void *context;                    /** @param[in]: Context with which callback function will be invoked */
    int domain;                       /** @param[in]: DSP domain id */
    fastrpc_notif_fn_t notifier_fn;   /** @param[in]: Notification function pointer */
    uint32_t timeout;                 /** @param[in]: RPC call timeout (in ms) */
} remote_rpc_notif_register_t_v2;

/**
 * Structure for remote_session_control,
 * used with FASTRPC_PD_INITMEM_SIZE request ID
 * to set signed userpd initial memory size
 **/
struct remote_rpc_pd_initmem_size {
    /** Remote subsystem domain ID, pass -1 to set params for all domains **/
    int domain;
    /** Initial memory allocated for remote userpd, minimum value : 3MB, maximum value 200MB **/
                                     /** Unsupported for unsigned user PD, for unsigned user PD init mem size is fixed at 5MB **/
    uint32_t pd_initmem_size;
};

/**
 * Structure for remote_session_control,
 * used with FASTRPC_RESERVE_SESSION request ID
 * to reserve new fastrpc session of the user PD on the DSP.
 * Default sesion is always 0 and remains available for any module opened without Session ID.
 * New session reservation starts with session ID 1.
**/
typedef struct remote_rpc_reserve_new_session {
    /** @param[in] : Domain name of DSP, on which session need to be reserved */
    char *domain_name;
    /** @param[in] : Domain name length, without NULL character */
    uint32_t domain_name_len;
    /** @param[in] : Session name of the reserved sesssion */
    char *session_name;
    /** @param[in] : Session name length, without NULL character */
    uint32_t session_name_len;
    /** @param[out] : Effective Domain ID is the identifier of the session.
                                             * Effective Domain ID is the unique identifier representing the session(PD) on DSP.
                                             * Effective Domain ID needs to be used in place of Domain ID when application has multiple sessions.
                                             */
    uint32_t effective_domain_id;
    /** @param[out] : Session ID of the reserved session.
                                             * An application can have multiple sessions(PDs) created on DSP.
                                             * session_id 0 is the default session. Clients can reserve session starting from 1.
                                             * Currently only 2 sessions are supported session_id 0 and session_id 1.
                                             */
    uint32_t session_id;
} remote_rpc_reserve_new_session_t;

/**
 * Structure for remote_session_control,
 * used with FASTRPC_GET_EFFECTIVE_DOMAIN_ID request ID
 * to get effective domain id of fastrpc session on the user PD of the DSP
**/
typedef struct remote_rpc_effective_domain_id {
    /** @param[in] : Domain name of DSP */
    char *domain_name;
    /** @param[in] : Domain name length, without NULL character */
    uint32_t domain_name_len;
    /** @param[in] : Session ID of the reserved session. 0 can be used for Default session */
    uint32_t session_id;
    /** @param[out] : Effective Domain ID of session */
    uint32_t effective_domain_id;
} remote_rpc_effective_domain_id_t;

/**
 * Structure for remote_session_control,
 * used with FASTRPC_GET_URI request ID
 * to get the URI needed to load the module in the fastrpc user PD on the DSP
**/
typedef struct remote_rpc_get_uri {
    /** @param[in] : Domain name of DSP */
    char *domain_name;
    /** @param[in] : Domain name length, without NULL character */
    uint32_t domain_name_len;
    /** @param[in] : Session ID of the reserved session. 0 can be used for Default session */
    uint32_t session_id;
    /** @param[in] : URI of the module, found in the auto-generated header file*/
    char *module_uri ;
    /** @param[in] : Module URI length, without NULL character */
    uint32_t module_uri_len;
    /** @param[out] : URI containing module, domain and session.
                                             * Memory for uri need to be pre-allocated with session_uri_len size.
                                             * Typically session_uri_len is 30 characters more than Module URI length.
                                             * If size of uri is beyond session_uri_len, remote_session_control fails with AEE_EBADSIZE
                                             */
    char *uri ;
    /** @param[in] : URI length */
    uint32_t uri_len;
} remote_rpc_get_uri_t;

/*
 * Structure used for request ID `FASTRPC_MAX_THREAD_PARAM`
 * in remote session control interface
 */
struct remote_rpc_set_max_thread {
	/*
	 * Remote subsystem effective domain ID, -1 to set params for all
	 * domains.
	 */
	int domain;

	/*
	 * Max thread config for unsinged PD. Minimum value : 128,
	 * maximum value 256.
	 */
	unsigned int max_num_threads;
};

/**
 * Structure for remote_session_control, used with FASTRPC_CONTEXT_CREATE request,
 * to create a multidomain fastrpc context
**/
typedef struct fastrpc_context_create {
	/** @param[in] : List of effective domain IDs on which session needs to be
					 created. Needs to be allocated and populated by user.
					 A new effective domain id CANNOT be added to an existing context. */
	uint32_t *effec_domain_ids;

	/** @param[in] : Number of domain ids.
					 Size of effective domain ID array. */
	uint32_t num_domain_ids;

	/** @param[in] : Type of create request (unused) */
	uint64_t flags;

	/** @param[out] : Multi-domain context handle */
	uint64_t ctx;
} fastrpc_context_create;

/** struct to be used with FASTRPC_CONTEXT_DESTROY request ID */
typedef struct fastrpc_context_destroy {
	/** @param[in] : Fastrpc multi-domain context */
	uint64_t ctx;

	/** @param[in] : Type of destroy request (unused) */
	uint64_t flags;
} fastrpc_context_destroy;

/**
 * Structure used with request ID `FASTRPC_REMOTE_PROCESS_STATE_DUMP` to
 * log the state of remote-process.
 **/
typedef struct {
	/** [in] Effective domain id of remote session whose state needs to be logged */
	int effec_domain_id;

	/** [in] Level of logging (unused for now) */
	uint32_t level;

	/**
	  * [in] String buffer which fastrpc will populate with the remote
	  * process state.
	  * Needs to be allocated by client and must be a dma buffer.
	  */
	void *ptr;

	/** [in] fd of buffer */
	int fd;

	/**
	  * [in] Size of buffer. Recommended size at least 1MB but more could
	  * be needed if remote process has large number of buffer mappings or
	  * power votes or libraries.
	  */
	uint32_t size;
} fastrpc_remote_proc_state;

/** DSP search path request type */
enum fastrpc_search_path {
    /** Get DSP search path */
    FASTRPC_GET_PATH = 0,
    /** Set DSP search path */
    FASTRPC_SET_PATH,
};

/**
  * Payload structure for the `FASTRPC_MANAGE_DSP_LIBRARY_PATH` request id
  * with remote_session_control api to set / get the search paths for
  * loading dsp libraries.
  */
typedef struct {
    /**
      * [in] Effective domain id of remote session whose search-path needs
      * to be managed. Set -1, to set same search path for all domains.
      * -1 cannot be passed with FASTRPC_GET_PATH request.
      */
    int effec_domain_id;

    /** [in] Type of request */
    enum fastrpc_search_path type;

    /**
      * [in/out] For set request, this will be treated as input and client
      * needs to provide the path(s) here. They will be prepended to list
      * of search paths. Separate each location with a ;
      * For eg: /path/to/folder1;/path/to/folder2
      *
      * For get request, this will be treated as output and client only
      * needs to allocate this buffer of enough size. Fastrpc will
      * populate the buffer with list of all search paths, each separated
      * by a ;
      */
    char *path;

    /**
      * [in/out] For both requests, this will be treated as input and client
      * needs to provide the total size of the 'path' buffer (in bytes) to
      * be used by fastrpc.
      * For get request, if this size is not sufficient to return all the
      * search paths, an error will be returned and pathlen will be updated
      * with required length.
      */
    uint32_t pathlen;
} fastrpc_manage_dsp_lib_path;

/**
 * Request IDs for remote session control interface
 **/
enum session_control_req_id {
    /** Reserved */
    FASTRPC_RESERVED_1,

    /** Set thread parameters like priority and stack size */
    FASTRPC_THREAD_PARAMS,

    /** Handle the unsigned module offload request, to be called before remote_handle_open() */
    DSPRPC_CONTROL_UNSIGNED_MODULE,

    /** Reserved */
    FASTRPC_RESERVED_2,

    /** To increase/decrease default thread priority */
    FASTRPC_RELATIVE_THREAD_PRIORITY,

    /** Reserved */
    FASTRPC_RESERVED_3,

    /** Kill remote process */
    FASTRPC_REMOTE_PROCESS_KILL,

    /** Close all open handles of requested domain */
    FASTRPC_SESSION_CLOSE,

    /** Enable PD dump feature */
    FASTRPC_CONTROL_PD_DUMP,

    /** Trigger Exception in the remote process */
    FASTRPC_REMOTE_PROCESS_EXCEPTION,

    /** Query type of process defined by enum fastrpc_process_type */
    FASTRPC_REMOTE_PROCESS_TYPE,

    /** Enable DSP User process status notifications */
    FASTRPC_REGISTER_STATUS_NOTIFICATIONS,

    /** Set signed userpd initial memory size  */
    FASTRPC_PD_INITMEM_SIZE,

    /** Reserve new FastRPC session */
    FASTRPC_RESERVE_NEW_SESSION,

    /** Get effective domain ID of a FastRPC session */
    FASTRPC_GET_EFFECTIVE_DOMAIN_ID,

    /** Creates the URI needed to load a module in the DSP User PD */
    FASTRPC_GET_URI,

    /** Set max thread value for unsigned PD */
    FASTRPC_MAX_THREAD_PARAM,

    /** Create or attaches to remote session(s) on one or more domains */
    FASTRPC_CONTEXT_CREATE,

    /** Destroy or detach from remote sessions */
    FASTRPC_CONTEXT_DESTROY,

    /** Log current state of remote process on hlos */
    FASTRPC_REMOTE_PROCESS_STATE_DUMP,

    /** Set / get DSP_LIBRARY_PATH for a specific remote session */
    FASTRPC_MANAGE_DSP_LIBRARY_PATH,

    /**
     * Use remote_rpc_notif_register_t or remote_rpc_notif_register_t_v2
     * for deregistration. To update or reset the timeout, create a new
     * context and deregister the previous one. The context, domain, and
     * callback function pointer must match those used during registration.
     */
    FASTRPC_DEREGISTER_STATUS_NOTIFICATIONS,
};


/**
 * Memory map control flags for using with remote_mem_map() and remote_mem_unmap()
 **/
enum remote_mem_map_flags {
/**
 * Create static memory map on remote process with default cache configuration (writeback).
 * Same remoteVirtAddr will be assigned on remote process when fastrpc call made with local virtual address.
 * @Map lifetime
 * Life time of this mapping is until user unmap using remote_mem_unmap or session close.
 * No reference counts are used. Behavior of mapping multiple times without unmap is undefined.
 * @Cache maintenance
 * Driver clean caches when virtual address passed through RPC calls defined in IDL as a pointer.
 * User is responsible for cleaning cache when remoteVirtAddr shared to DSP and accessed out of fastrpc method invocations on DSP.
 * @recommended usage
 * Map buffers which are reused for long time or until session close. This helps to reduce fastrpc latency.
 * Memory shared with remote process and accessed only by DSP.
 */
    REMOTE_MAP_MEM_STATIC,

    /** Always keep as last member */
    REMOTE_MAP_MAX_FLAG,
 };

/**
 * @enum fastrpc_map_flags for fastrpc_mmap and fastrpc_munmap
 * @brief Types of maps with cache maintenance
 */
enum fastrpc_map_flags {
    /**
     * Map memory pages with RW- permission and CACHE WRITEBACK.
     * Driver will clean cache when buffer passed in a FastRPC call.
     * Same remote virtual address will be assigned for subsequent
     * FastRPC calls.
     */
    FASTRPC_MAP_STATIC,

    /** Reserved for compatibility with deprecated flag */
    FASTRPC_MAP_RESERVED,

    /**
     * Map memory pages with RW- permission and CACHE WRITEBACK.
     * Mapping tagged with a file descriptor. User is responsible for
     * maintenance of CPU and DSP caches for the buffer. Get virtual address
     * of buffer on DSP using HAP_mmap_get() and HAP_mmap_put() functions.
     */
    FASTRPC_MAP_FD,

    /**
     * Mapping delayed until user calls HAP_mmap() and HAP_munmap()
     * functions on DSP. User is responsible for maintenance of CPU and DSP
     * caches for the buffer. Delayed mapping is useful for users to map
     * buffer on DSP with other than default permissions and cache modes
     * using HAP_mmap() and HAP_munmap() functions.
     */
    FASTRPC_MAP_FD_DELAYED,

    /** Reserved for compatibility **/
    FASTRPC_MAP_RESERVED_4,
    FASTRPC_MAP_RESERVED_5,
    FASTRPC_MAP_RESERVED_6,
    FASTRPC_MAP_RESERVED_7,
    FASTRPC_MAP_RESERVED_8,
    FASTRPC_MAP_RESERVED_9,
    FASTRPC_MAP_RESERVED_10,
    FASTRPC_MAP_RESERVED_11,
    FASTRPC_MAP_RESERVED_12,
    FASTRPC_MAP_RESERVED_13,
    FASTRPC_MAP_RESERVED_14,
    FASTRPC_MAP_RESERVED_15,

    /**
     * This flag is used to skip CPU mapping,
     * otherwise behaves similar to FASTRPC_MAP_FD_DELAYED flag.
     */
    FASTRPC_MAP_FD_NOMAP,

    /**
     * The below two flags work the same as FASTRPC_MAP_FD and FASTRPC_MAP_FD_DELAYED
     * but allow the user to map into the extended address space on DSP
     */

    FASTRPC_MAP_FD_EXTENDED,
    FASTRPC_MAP_FD_DELAYED_EXTENDED,

    /** Always keep this as the last member */
    FASTRPC_MAP_MAX,
};

/**
 * @enum fastrpc_map_attrs for fastrpc_mem_request payload
 * @brief Attributes to map and unmap the buffer
 */
enum fastrpc_map_attrs {
    /** Default attribute for all map and unmap requests */
    FASTRPC_MAP_ATTR_DEFAULT = 0,

    /**
     * When set during buffer mapping, it reserves the IOVA region so it
     * remains reserved after unmapping also.
     * When set during unmapping, it removes only the IOMMU mapping while
     * keeping the IOVA reserved for future reuse during subsequent
     * mappings of the same buffer.
     * To unmap a buffer with this attribute, it should've also been mapped
     * with the same attribute.
     */
    FASTRPC_MAP_ATTR_RETAIN_IOVA = 1024,

    /** Always keep this as the last member */
    FASTRPC_MAP_ATTR_MAX,
};

/**
 * @enum fastrpc_mem_req_id
 * @brief Memory request ids
 */
typedef enum
{
    /** To create a mapping associated with file descriptor */
    FASTRPC_MEM_MAP      = 0,

    /** To remove a mapping associated with file descriptor */
    FASTRPC_MEM_UNMAP    = 1,

    /** To set custom name for DMA buffers */
    FASTRPC_MEM_SETNAME  = 2
} fastrpc_mem_req_id;

/**
 * @struct fastrpc_mem_map_payload
 * Payload structure for FASTRPC_MEM_MAP request
 * to create a mapping associated with file descriptor.
 */
typedef struct {
    /** @param[in]: Effective domain id of a fastrpc remote session */
    int effec_domain_id;

    /** @param[in]: File descriptor */
    int fd;

    /** @param[in]: Buffer length */
    size_t length;

    /** @param[in]: Flags used to map the buffer */
    enum fastrpc_map_flags flags;

    /** @param[in]: Attributes used to map the buffer */
    enum fastrpc_map_attrs attrs;
} fastrpc_mem_map_payload;

/**
 * @struct fastrpc_mem_unmap_payload
 * Payload structure for FASTRPC_MEM_UNMAP request
 * to remove a mapping associated with file descriptor.
 */
typedef struct {
    /** @param[in]: Effective domain id of a fastrpc remote session */
    int effec_domain_id;

    /** @param[in]: File descriptor */
    int fd;

    /** @param[in]: Attributes used to unmap the buffer */
    enum fastrpc_map_attrs attrs;
} fastrpc_mem_unmap_payload;

/**
 * @struct fastrpc_mem_setname_payload
 * @brief Payload structure for FASTRPC_MEM_SETNAME request
 * to set name for allocated buffer
 */
typedef struct {
    /** [in] File descriptor */
    int fd;

    /**
     * [in] Name to set for given DMA buffer limited to
     *  RPCMEM_MAX_BUF_NAME_LEN including null terminator
     */
    char bufname[RPCMEM_MAX_BUF_NAME_LEN];
} fastrpc_mem_setname_payload;

/**
 * @struct fastrpc_mem_req_payload
 * @brief Payload for fastrpc_mem_request API
 * New request payload structures should be
 * added to the union.
 */
typedef struct
{
    fastrpc_mem_req_id request_id;
    union {
        fastrpc_mem_map_payload mmap;
        fastrpc_mem_unmap_payload munmap;
        fastrpc_mem_setname_payload bufname_data;
    };
} fastrpc_mem_req_payload;

#define MAX_DOMAIN_NAMELEN 30

/* Position of domain type in flags */
#define DOMAINS_LIST_FLAGS_TYPE_POS 5

/* Helper macro to set domain type in flags */
#define DOMAINS_LIST_FLAGS_SET_TYPE(flags, type) \
	(flags | (type & ((1 << DOMAINS_LIST_FLAGS_TYPE_POS) - 1)))

/**
 * @enum fastrpc_domain_type
 * @brief Indicates the type of domains (DSPs) present in the system
 */
typedef enum {
	/** Flag to be used to query list of all available domains */
	FASTRPC_ALL_DOMAINS,
	FASTRPC_NSP,
	FASTRPC_LPASS,
	FASTRPC_SDSP,
	FASTRPC_MDSP,
	FASTRPC_HPASS,
} fastrpc_domain_type;

/**
 * @struct fastrpc_domain
 * @brief Describes the details of each domain
 */
typedef struct {
	/**
	 * @param : Logical domain id of the dsp.
	 * This can be used to query the capabilities of the dsp and
	 * can change with every reboot of device depending on the order
	 * of domain enumeration.
	 * This is NOT the same as effective domain id. To get the effective
	 * domain id of a particular session on this domain, pass the corresponding
	 * domain name with the `FASTRPC_GET_EFFECTIVE_DOMAIN_ID` request.
	 */
	int id;

	/**
	 * @param : Instance id of the domain.
	 * Actual HW instance id of the dsp domain. Most client apps do not need
	 * to use this parameter. This is required only for apps which directly
	 * communicate with the dsp domain independently of fastrpc.
	 */
	int instance_id;

	/**
	 * @param : Name of domain.
	 * To be appended with module uri while opening remote handle,
	 * or for querying the effective domain id on a specific session
	 * on this domain.
	 */
	char name[MAX_DOMAIN_NAMELEN];

	/**
	 * @param : Type of DSP, of 'fastrpc_domain_type'.
	 */
	fastrpc_domain_type type;

	/**
	 * @param : Status of domain: 0 if domain is down
	 *                            non-zero if domain is up
	 */
	int status;

	/**
	 * @param : Card on which domain is present (for future use).
	 */
	uint32_t card;

	/**
	 * @param : SoC on which domain is present (for future use).
	 */
	uint32_t soc_id;

	/**
	 * @param : Reserved (for future use).
	 */
	uint32_t reserved[10];
} fastrpc_domain;

/**
 * @struct fastrpc_domain_info
 * @brief Structure used with 'FASTRPC_GET_DOMAINS' request id
 * to query the list of available domains in the system.
 */
typedef struct {
	/**
	 * @param[in/out] : Domains-info array pointer.
	 * Array needs to be allocated by client with size of array specified
	 * in 'max_domains'. Array will be populated by fastrpc with list of
	 * available domains.
	 * To query number of domains first, pass NULL pointer.
	 */
	fastrpc_domain *domains;

	/**
	 * @param[in] : Size of the 'domains' array allocated by user.
	 * This has to be greater than or equal to the actual number of available
	 * domains. To query number of domains first, pass 0 in this field.
	 */
	int max_domains;

	/**
	 * @param[out] : This field will be populated with the total number
	 * of available domains. While reading the domains-info in the array,
	 * read only until 'num_domains' elements.
	 */
	int num_domains;

	/**
	 * @param[in] : Bit-mask for the type of request, to be populated by client.
	 * Use DOMAINS_LIST_FLAGS_SET_TYPE macro to configure domain-type in flags.
	 * Only domains of this type will be returned in the 'domains' array.
	 * To get list of all available domains, use 'ALL_DOMAINS' type.
	 * Other bits reserved for future use.
	 */
	uint64_t flags;
} fastrpc_domains_info;

/**
 * fastrpc_domain_status_notif_fn_t
 * Callback fired when a domain status changes.
 *
 * @param context   Opaque pointer supplied at registration time.
 * @param domain    Pointer to the full fastrpc_domain descriptor. Valid only
 *                  for the duration of the callback; copy the struct if you
 *                  need it beyond the call.
 *                    - Read domain->status for the current state:
 *                        Non-zero: domain is up
 *                        Zero:     domain is down
 *                    - The status value reflects the current state; no
 *                      follow-up FASTRPC_GET_DOMAINS call is needed.
 * @retval          0 on success
 *
 * Immediate callbacks on registration:
 *   - Upon successful registration, the callback is immediately invoked once
 *     for each existing domain that matches the filter (domain_id / domain_type).
 *     This allows clients to learn the current state of all matching domains
 *     without a separate FASTRPC_GET_DOMAINS call.
 *   - After that, the callback fires whenever a matching domain changes status.
 *
 * Constraints:
 *   - All callbacks are serialized: only one callback fires at a time.
 *   - The callback must return promptly; blocking it stalls all subsequent
 *     domain event notifications for every registered client..
 *   - The following calls must NOT be made from within the callback as they
 *     would cause deadlock:
 *       * remote_handle64_open
 *       * remote_handle64_invoke
 *       * remote_system_request
 */
typedef int (*fastrpc_domain_status_notif_fn_t)(void *context,
                                                const fastrpc_domain *domain);

/**
 * @struct fastrpc_domain_notif_info
 * @brief  Payload used with FASTRPC_REGISTER_DOMAIN_STATUS_NOTIFICATIONS and
 *         FASTRPC_DEREGISTER_DOMAIN_STATUS_NOTIFICATIONS request ids.
 *
 * Filtering rules:
 *   domain_id > 0  (domain_type ignored) — watch only that specific logical domain ID
 *   domain_id == 0, domain_type == FASTRPC_ALL_DOMAINS — wildcard, watch every domain
 *   domain_id == 0, domain_type == FASTRPC_NSP (or other) — watch all domains of that type
 *
 * On successful registration, the callback is immediately invoked once for each
 * existing domain that matches the filter, delivering the current status of each
 * domain. Subsequent callbacks fire whenever a matching domain changes status.
 *
 * For deregistration, the (domain_id, domain_type, context, notifier_fn) tuple must
 * match the original registration exactly.
 */
typedef struct {
	/**
	 * @param[in]: Domain ID to watch (as returned in fastrpc_domain.domain_id
	 *             by a FASTRPC_GET_DOMAINS query), or 0 to use domain_type filter.
	 */
	int domain_id;

	/**
	 * @param[in]: Domain type filter (used only when domain_id == 0).
	 * Use FASTRPC_ALL_DOMAINS as a wildcard to watch every domain.
	 */
	fastrpc_domain_type domain_type;

	/**
	 * @param[in]: Opaque context pointer passed back in every callback invocation.
	 */
	void *context;

	/**
	 * @param[in]: Callback function invoked on domain status change.
	 * Also invoked immediately upon registration for each existing domain
	 * matching the filter. Must not call remote_handle_open,
	 * remote_handle_invoke, or remote_system_request from within the callback.
	 */
	fastrpc_domain_status_notif_fn_t notifier_fn;
} fastrpc_domain_notif_info;

/**
 * @enum system_req_id
 * @brief Request ID for remote_system_request API
 */
typedef enum {
	/** Query list of available domains */
	FASTRPC_GET_DOMAINS = 0,

	/**
         * Register a callback to receive domain status change notifications.
	 *  The callback is immediately invoked for each existing matching domain
	 *  upon registration, then fires on subsequent status changes.
         */
	FASTRPC_REGISTER_DOMAIN_STATUS_NOTIFICATIONS,

	/** Deregister a previously registered domain status notification callback */
	FASTRPC_DEREGISTER_DOMAIN_STATUS_NOTIFICATIONS,
} system_req_id;

/**
 * @struct system_req_payload
 * @brief Payload for remote_system_request API
 */
typedef struct {
	system_req_id id;
	union {
		fastrpc_domains_info sys;
		fastrpc_domain_notif_info domain_notif;
	};
} system_req_payload;

/**
 * remote_system_request
 * API to get system info like list of available domains
 * @param req, payload containing system info and request ID
 * @return, 0 on Success
 */
int remote_system_request(system_req_payload *req);

/**
 * Attributes for remote_register_buf_attr/remote_register_buf_attr2
 **/
#define FASTRPC_ATTR_NONE          0          /** No attribute to set.*/
#define FASTRPC_ATTR_NON_COHERENT  2          /** Attribute to map a buffer as dma non-coherent,
                                                 Driver perform cache maintenance.*/
#define FASTRPC_ATTR_COHERENT      4          /** Attribute to map a buffer as dma coherent,
                                                 Driver skips cache maintenenace
                                                 It will be ignored if a device is marked as dma-coherent in device tree.*/
#define FASTRPC_ATTR_KEEP_MAP      8          /** Attribute to keep the buffer persistant
                                                 until unmap is called explicitly.*/
#define FASTRPC_ATTR_NOMAP         16         /** Attribute for secure buffers to skip
                                                 smmu mapping in fastrpc driver*/
#define FASTRPC_ATTR_FORCE_NOFLUSH 32         /** Attribute to map buffer such that flush by driver is skipped for that particular buffer
                                                 client has to perform cache maintenance*/
#define FASTRPC_ATTR_FORCE_NOINVALIDATE 64    /** Attribute to map buffer such that invalidate by driver is skipped for that particular buffer
                                                 client has to perform cache maintenance */
#define FASTRPC_ATTR_TRY_MAP_STATIC 128       /** Attribute for persistent mapping a buffer
                                                 to remote DSP process during buffer registration
                                                 with FastRPC driver. This buffer will be automatically
                                                 mapped during fastrpc session open and unmapped either
                                                 at unregister or session close. FastRPC library tries
                                                 to map buffers and ignore errors in case of failure.
                                                 pre-mapping a buffer reduces the FastRPC latency.
                                                 This flag is recommended only for buffers used with
                                                 latency critical rpc calls */

#define FASTRPC_ATTR_IMPORT_BUFFER 256        /** Attribute that specifies a buffer to be imported.
                                                  Rather than registering a buffer allocated by rpcmem library,
                                                  it will import and register a buffer that has been allocated
                                                  using other methods. It allows non-rpcmem buffers to be registered
                                                  with FastRPC without having to allocate a new buffer using rpcmem
                                                  and copying the old buffer into the rpcmem buffer.
                                                  Note: Only availible on WoS */

#define FASTRPC_ATTR_READ_ONLY 512            /** Attribute to be used when the user buffer to be registered is READ_ONLY.
                                                  Current use case is available only on WoS, where this attr should be ORed with
                                                  FASTRPC_ATTR_IMPORT_BUFFER, when the intent to import a read_only user buffer
                                                  Note: Only availible on WoS */

/**
 * REMOTE_MODE_PARALLEL used with remote_set_mode
 * This is the default mode for the driver.  While the driver is in parallel
 * mode it will try to invalidate output buffers after it transfers control
 * to the dsp.  This allows the invalidate operations to overlap with the
 * dsp processing the call.  This mode should be used when output buffers
 * are only read on the application processor and only written on the aDSP.
 */
#define REMOTE_MODE_PARALLEL  0

/**
 * REMOTE_MODE_SERIAL used with remote_set_mode
 * When operating in SERIAL mode the driver will invalidate output buffers
 * before calling into the dsp.  This mode should be used when output
 * buffers have been written to somewhere besides the aDSP.
 */
#define REMOTE_MODE_SERIAL    1


#ifdef _WIN32
#include "remote_wos_ext.h" /** For function pointers of remote APIs */
#endif


/**
 * remote_handle()_open
 * Opens a remote_handle "name"
 * returns 0 on success
 **/
__QAIC_REMOTE_EXPORT __QAIC_RETURN int __QAIC_REMOTE(remote_handle_open)(__QAIC_IN_CHAR  const char* name, __QAIC_OUT remote_handle *ph) __QAIC_REMOTE_ATTRIBUTE;
__QAIC_REMOTE_EXPORT __QAIC_RETURN int __QAIC_REMOTE(remote_handle64_open)( __QAIC_IN_CHAR  const char* name, __QAIC_OUT  remote_handle64 *ph) __QAIC_REMOTE_ATTRIBUTE;


/**
 * invokes the remote handle
 * see retrive macro's on dwScalars format
 * pra, contains the arguments in the following order, inbufs, outbufs, inhandles, outhandles.
 * implementors should ignore and pass values asis that the transport doesn't understand.
 */
__QAIC_REMOTE_EXPORT __QAIC_RETURN int __QAIC_REMOTE(remote_handle_invoke)(__QAIC_IN remote_handle h, __QAIC_IN uint32_t dwScalars, __QAIC_IN remote_arg *pra) __QAIC_REMOTE_ATTRIBUTE;
__QAIC_REMOTE_EXPORT __QAIC_RETURN int __QAIC_REMOTE(remote_handle64_invoke)(__QAIC_IN remote_handle64 h, __QAIC_IN uint32_t dwScalars, __QAIC_IN remote_arg *pra) __QAIC_REMOTE_ATTRIBUTE;


/**
 * remote_handle()_close
 * closes the remote handle
 */
__QAIC_REMOTE_EXPORT __QAIC_RETURN int __QAIC_REMOTE(remote_handle_close)(__QAIC_IN remote_handle h) __QAIC_REMOTE_ATTRIBUTE;
__QAIC_REMOTE_EXPORT __QAIC_RETURN int __QAIC_REMOTE(remote_handle64_close)(__QAIC_IN remote_handle64 h) __QAIC_REMOTE_ATTRIBUTE;


/**
 * remote_handle_control
 * Set remote handle control parameters
 *
 * @param req, request ID defined by handle_control_req_id
 * @param data, address of structure with parameters
 * @param datalen, length of data
 * @retval, 0 on success
 */
__QAIC_REMOTE_EXPORT __QAIC_RETURN int __QAIC_REMOTE(remote_handle_control)(__QAIC_IN uint32_t req,  __QAIC_IN_LEN(datalen)  void* data,  __QAIC_IN uint32_t datalen) __QAIC_REMOTE_ATTRIBUTE;
__QAIC_REMOTE_EXPORT __QAIC_RETURN int __QAIC_REMOTE(remote_handle64_control)(__QAIC_IN remote_handle64 h, __QAIC_IN uint32_t req, __QAIC_IN_LEN(datalen)  void* data, __QAIC_IN uint32_t datalen) __QAIC_REMOTE_ATTRIBUTE;


/**
 * remote_session_control
 * Set remote session parameters
 *
 * @param req, request ID
 * @param data, address of structure with parameters
 * @param datalen, length of data
 * @retval, 0 on success
 * remote_session_control with FASTRPC_REMOTE_PROCESS_KILL req ID, possible error codes
 * are AEE_ENOSUCH, AEE_EBADPARM, AEE_EINVALIDDOMAIN. Other than this errors codes treated as
 * retuned from fastRPC framework.
 */
__QAIC_REMOTE_EXPORT __QAIC_RETURN int __QAIC_REMOTE(remote_session_control)(__QAIC_IN uint32_t req, __QAIC_IN_LEN(datalen) void *data, __QAIC_IN uint32_t datalen) __QAIC_REMOTE_ATTRIBUTE;


/**
 * remote_handle()_invoke_async
 * invokes the remote handle asynchronously
 *
 * desc, descriptor contains type of asyncjob. context and call back function(if any)
 * see retrive macro's on dwScalars format
 * pra, contains the arguments in the following order, inbufs, outbufs, inhandles, outhandles.
 * all outbufs need to be either allocated using rpcmem_alloc or registered ION buffers using register_buf
 * implementors should ignore and pass values as is that the transport doesn't understand.
 */
__QAIC_REMOTE_EXPORT __QAIC_RETURN int __QAIC_REMOTE(remote_handle_invoke_async)(__QAIC_IN remote_handle h, __QAIC_IN fastrpc_async_descriptor_t *desc, __QAIC_IN uint32_t dwScalars, __QAIC_IN remote_arg *pra) __QAIC_REMOTE_ATTRIBUTE;
__QAIC_REMOTE_EXPORT __QAIC_RETURN int __QAIC_REMOTE(remote_handle64_invoke_async)(__QAIC_IN remote_handle64 h, __QAIC_IN fastrpc_async_descriptor_t *desc, __QAIC_IN uint32_t dwScalars, __QAIC_IN remote_arg *pra) __QAIC_REMOTE_ATTRIBUTE;


/**
 * fastrpc_async_get_status
 * Get status of Async job. This can be used to query the status of a Async job
 *
 * @param jobid, jobid returned during Async job submission.
 * @param timeout_us, timeout in micro seconds
 *                    timeout = 0, returns immediately with status/result
 *                    timeout > 0, waits for specified time and then returns with status/result
 *                    timeout < 0. waits indefinetely until job completes
 * @param result, integer pointer for the result of the job
 *                0 on success
 *                error code on failure
 * @retval, 0 on job completion and result of job is part of @param result
 *          AEE_EBUSY, if job status is pending and is not returned from DSP
 *          AEE_EBADPARM, if job id is invalid
 *          AEE_EFAILED, FastRPC internal error
 *
 */
__QAIC_REMOTE_EXPORT __QAIC_RETURN int __QAIC_REMOTE(fastrpc_async_get_status)(__QAIC_IN fastrpc_async_jobid jobid,__QAIC_IN int timeout_us,__QAIC_OUT int *result);


/**
 * fastrpc_release_async_job
 * Release Async job after receiving status either through callback/poll
 *
 * @param jobid, jobid returned during Async job submission.
 * @retval, 0 on success
 *          AEE_EBUSY, if job status is pending and is not returned from DSP
 *          AEE_EBADPARM, if job id is invalid
 *
 */
__QAIC_REMOTE_EXPORT __QAIC_RETURN int __QAIC_REMOTE(fastrpc_release_async_job)(__QAIC_IN fastrpc_async_jobid jobid);


/**
 * remote_mmap
 * map memory to the remote domain
 *
 * @param fd, fd assosciated with this memory
 * @param flags, flags to be used for the mapping
 * @param vaddrin, input address
 * @param size, size of buffer
 * @param vaddrout, output address
 * @retval, 0 on success
 */
__QAIC_REMOTE_EXPORT __QAIC_RETURN int __QAIC_REMOTE(remote_mmap)(__QAIC_IN int fd, __QAIC_IN uint32_t flags, __QAIC_IN uint32_t vaddrin, __QAIC_IN int size, __QAIC_OUT uint32_t* vaddrout) __QAIC_REMOTE_ATTRIBUTE;


/**
 * remote_munmap
 * unmap memory from the remote domain
 *
 * @param vaddrout, remote address mapped
 * @param size, size to unmap.  Unmapping a range partially may  not be supported.
 * @retval, 0 on success, may fail if memory is still mapped
 */
__QAIC_REMOTE_EXPORT __QAIC_RETURN int __QAIC_REMOTE(remote_munmap)(__QAIC_IN uint32_t vaddrout, __QAIC_IN int size) __QAIC_REMOTE_ATTRIBUTE;


/**
 * remote_mem_map
 * Map memory to the remote process on a selected DSP domain
 *
 * @domain: DSP domain ID. Use -1 for using default domain.
 *          Default domain is selected based on library lib(a/m/s/c)dsprpc.so library linked to application.
 * @fd: file descriptor of memory
 * @flags: enum remote_mem_map_flags type of flag
 * @virtAddr: virtual address of buffer
 * @size: buffer length
 * @remoteVirtAddr[out]: remote process virtual address
 * @retval, 0 on success
 */
__QAIC_REMOTE_EXPORT __QAIC_RETURN int __QAIC_REMOTE(remote_mem_map)(__QAIC_IN int domain, __QAIC_IN int fd, __QAIC_IN int flags, __QAIC_IN uint64_t virtAddr, __QAIC_IN size_t size, __QAIC_OUT uint64_t* remoteVirtAddr) __QAIC_REMOTE_ATTRIBUTE;


/**
 * remote_mem_unmap
 * Unmap memory to the remote process on a selected DSP domain
 *
 * @domain: DSP domain ID. Use -1 for using default domain. Get domain from multi-domain handle if required.
 * @remoteVirtAddr: remote process virtual address received from remote_mem_map
 * @size: buffer length
 * @retval, 0 on success
 */
__QAIC_REMOTE_EXPORT __QAIC_RETURN int __QAIC_REMOTE(remote_mem_unmap)(__QAIC_IN int domain, __QAIC_IN uint64_t remoteVirtAddr, __QAIC_IN size_t size) __QAIC_REMOTE_ATTRIBUTE;


/**
 * remote_mmap64
 * map memory to the remote domain
 *
 * @param fd, fd associated with this memory
 * @param flags, flags to be used for the mapping
 * @param vaddrin, input address
 * @param size, size of buffer
 * @param vaddrout, output address
 * @retval, 0 on success
 */
__QAIC_REMOTE_EXPORT __QAIC_RETURN int __QAIC_REMOTE(remote_mmap64)(__QAIC_IN int fd, __QAIC_IN uint32_t flags, __QAIC_IN __QAIC_INT64PTR vaddrin, __QAIC_IN int64_t size, __QAIC_OUT __QAIC_INT64PTR *vaddrout) __QAIC_REMOTE_ATTRIBUTE;


/**
 * remote_munmap64
 * unmap memory from the remote domain
 *
 * @param vaddrout, remote address mapped
 * @param size, size to unmap.  Unmapping a range partially may  not be supported.
 * @retval, 0 on success, may fail if memory is still mapped
 */
__QAIC_REMOTE_EXPORT __QAIC_RETURN int __QAIC_REMOTE(remote_munmap64)(__QAIC_IN __QAIC_INT64PTR vaddrout, __QAIC_IN int64_t size) __QAIC_REMOTE_ATTRIBUTE;


/**
 * fastrpc_mmap
 * Creates a mapping on remote process for an ION buffer with file descriptor. New fastrpc session
 * will be opened if not already opened for the domain.
 *
 * @param domain, DSP domain ID of a fastrpc session
 * @param fd, ION memory file descriptor
 * @param addr, buffer virtual address on cpu
 * @param offset, offset from the beginning of the buffer
 * @param length, size of buffer in bytes
 * @param flags, controls mapping functionality on DSP. Refer fastrpc_map_flags enum definition for more information.
 *
 * @return, 0 on success, error code on failure.
 *          AEE_EALREADY Buffer already mapped. Multiple mappings for the same buffer are not supported.
 *          AEE_EBADPARM Bad parameters
 *          AEE_EFAILED Failed to map buffer
 *          AEE_ENOMEMORY Out of memory (internal error)
 *          AEE_EUNSUPPORTED Unsupported API on the target
 *          AEE_EEXPIRED Timeout waiting to map the buffer on DSP
 */
__QAIC_REMOTE_EXPORT __QAIC_RETURN int __QAIC_REMOTE(fastrpc_mmap)(__QAIC_IN int domain, __QAIC_IN int fd, __QAIC_IN void *addr, __QAIC_IN int offset, __QAIC_IN size_t length, __QAIC_IN enum fastrpc_map_flags flags)__QAIC_REMOTE_ATTRIBUTE;


/**
 * fastrpc_munmap
 * Removes a mapping associated with file descriptor.
 *
 * @param domain, DSP domain ID of a fastrpc session
 * @param fd, file descriptor
 * @param addr, buffer virtual address used for mapping creation
 * @param length, buffer length
 *
 * @return, 0 on success, error code on failure.
 *          AEE_EBADPARM Bad parameters
 *          AEE_EINVALIDFD Mapping not found for specified fd
 *          AEE_EFAILED Failed to map buffer
 *          AEE_EUNSUPPORTED Unsupported API on the target
 *          AEE_EEXPIRED Timeout waiting to unmap the buffer on DSP
 */
__QAIC_REMOTE_EXPORT __QAIC_RETURN int __QAIC_REMOTE(fastrpc_munmap)(__QAIC_IN int domain, __QAIC_IN int fd, __QAIC_IN void *addr, __QAIC_IN size_t length)__QAIC_REMOTE_ATTRIBUTE;


/**
 * Generic mem request API, which will decode request type
 * and use the payload to parse the input and output
 * for the request
 * @param mem_payload- payload containing mem info and request ID
 * @retval 0 on success.
 */
int fastrpc_mem_request(fastrpc_mem_req_payload *mem_payload);


/**
 * remote_register_buf/remote_register_buf_attr
 * Register a file descriptor for a buffer.
 * Users of fastrpc should register zero-copy buffer to enable
 * sharing that buffer to the dsp via the SMMU. The API is limited
 * to register buffer less than 2 GB only. Recommendation is to use
 * remote_register_buf_attr2 instead. API remote_register_buf_attr2
 * can now accept size up to 2 power(8*sizeof(size_t)).
 *
 * Some versions of libcdsprpc.so lack this
 * function, so users should set this symbol as weak.
 *
 * #pragma weak remote_register_buf
 * #pragma weak remote_register_buf_attr
 *
 * @param buf, virtual address of the buffer
 * @param size, size of the buffer
 * @fd, the file descriptor, callers can use -1 to deregister.
 * @attr, map buffer as coherent or non-coherent
 */
__QAIC_REMOTE_EXPORT __QAIC_RETURN void __QAIC_REMOTE(remote_register_buf)(__QAIC_IN_LEN(size) void* buf, __QAIC_IN int size, __QAIC_IN int fd) __QAIC_REMOTE_ATTRIBUTE;
__QAIC_REMOTE_EXPORT __QAIC_RETURN void __QAIC_REMOTE(remote_register_buf_attr)(__QAIC_IN_LEN(size) void* buf, __QAIC_IN int size, __QAIC_IN int fd, __QAIC_IN int attr) __QAIC_REMOTE_ATTRIBUTE;


/**
 * remote_register_buf_attr2
 * Register a file descriptor for a buffer. Users of fastrpc should
 * register zero-copy buffer to enable sharing that buffer to the
 * dsp via the SMMU.
 *
 * Some versions of libcdsprpc.so lack this
 * function, so users should set this symbol as weak.
 *
 * #pragma weak remote_register_buf_attr2
 *
 * @param buf, virtual address of the buffer
 * @param size, size of the buffer
 * @fd, the file descriptor, callers can use -1 to deregister.
 * @attr, setting attribute for the mapped buffer
 *		  refer to "Attributes for remote_register_buf_attr/remote_register_buf_attr2"
 *		  to set the required attribute value.
 */
__QAIC_REMOTE_EXPORT __QAIC_RETURN void __QAIC_REMOTE(remote_register_buf_attr2)(__QAIC_IN_LEN(size) void* buf, __QAIC_IN size_t size, __QAIC_IN int fd, __QAIC_IN int attr) __QAIC_REMOTE_ATTRIBUTE;


/**
 * remote_register_dma_handle/remote_register_dma_handle_attr
 * Register a dma handle with fastrpc.
 * This is only valid on Android with ION allocated memory.
 * Users of fastrpc should register a file descriptor allocated with
 * ION to enable sharing that memory to the dsp via the SMMU.
 *
 * Some versions of libadsprpc.so lack this function,
 * so users should set this symbol as weak.
 *
 * #pragma weak remote_register_dma_handle
 * #pragma weak remote_register_dma_handle_attr
 *
 * @fd, the file descriptor, callers can use -1 to deregister.
 * @param len, size of the buffer
 * @attr, map buffer as coherent or non-coherent or no-map
 */
__QAIC_REMOTE_EXPORT __QAIC_RETURN int __QAIC_REMOTE(remote_register_dma_handle)(__QAIC_IN int fd,__QAIC_IN uint32_t len) __QAIC_REMOTE_ATTRIBUTE;
__QAIC_REMOTE_EXPORT __QAIC_RETURN int __QAIC_REMOTE(remote_register_dma_handle_attr)(__QAIC_IN int fd,__QAIC_IN uint32_t len,__QAIC_IN uint32_t attr) __QAIC_REMOTE_ATTRIBUTE;


/**
 * remote_set_mode
 * Set the mode of operation.
 *
 * Some versions of libadsprpc.so lack this function,
 * so users should set this symbol as weak.
 *
 * #pragma weak  remote_set_mode
 *
 * @param mode, the mode
 * @retval, 0 on success
 */
__QAIC_REMOTE_EXPORT __QAIC_RETURN int __QAIC_REMOTE(remote_set_mode)(__QAIC_IN uint32_t mode) __QAIC_REMOTE_ATTRIBUTE;


/**
 * remote_register_fd
 * Register a file descriptor.
 * This can be used when users do not have a mapping to pass to the
 * RPC layer. The generated address is a mapping with PROT_NONE, any
 * access to this memory will fail, so it should only be used as an
 * ID to identify this file descriptor to the RPC layer. This API is
 * limited to buffer size less then 2 GB. Recommendation is to use
 * remote_register_fd2 for buffer of size > 2 power(8*sizeof(size_t)).
 *
 * To deregister use remote_register_buf(addr, size, -1).
 *
 * #pragma weak  remote_register_fd
 *
 * @param fd, the file descriptor.
 * @param size, size to of the buffer
 * @retval, (void*)-1 on failure, address on success.
 */
__QAIC_REMOTE_EXPORT __QAIC_RETURN void *__QAIC_REMOTE(remote_register_fd)(__QAIC_IN int fd,__QAIC_IN int size) __QAIC_REMOTE_ATTRIBUTE;

/**
 * remote_register_fd2
 * Register a file descriptor.
 * This can be used when users do not have a mapping to pass to
 * the RPC layer. The generated address is a mapping with PROT_NONE,
 * any access to this memory will fail, so it should only be used
 * as an ID to identify this file descriptor to the RPC layer.
 *
 * To deregister use remote_register_buf(addr, size, -1).
 *
 * #pragma weak  remote_register_fd2
 *
 * @param fd, the file descriptor.
 * @param size, size to of the buffer
 * @retval, (void*)-1 on failure, address on success.
 */
__QAIC_REMOTE_EXPORT __QAIC_RETURN void *__QAIC_REMOTE(remote_register_fd2)(__QAIC_IN int fd,__QAIC_IN size_t size) __QAIC_REMOTE_ATTRIBUTE;


#ifdef __cplusplus
}
#endif

#endif /// REMOTE_H


