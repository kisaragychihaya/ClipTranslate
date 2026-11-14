/*-----------------------------------------------------------------------------
   Copyright (c) 2025 QUALCOMM Technologies, Incorporated.
   All Rights Reserved.
   QUALCOMM Proprietary.
-----------------------------------------------------------------------------*/

#ifndef HAP_COMPUTE_RES_EXT_H_
#define HAP_COMPUTE_RES_EXT_H_

#ifdef __cplusplus
extern "C" {
#endif

/** Include the base header */
#include "HAP_compute_res.h"

/**
 * @defgroup types Macros and structures
 * @{
 */

/** Define API version */
#define HAP_COMPUTE_RES_EXT_API_VERSION                             0x0101u

/**
 *  @file HAP_compute_res_extended.h
 *  @brief Header file with APIs extending the base HAP_compute_res interface
 */

/* Event IDs */
#define HAP_COMPUTE_RES_EVENT_ACQUIRE                               1
#define HAP_COMPUTE_RES_EVENT_ACQUIRE_DONE                          2
#define HAP_COMPUTE_RES_EVENT_CACHED_ACQUIRE                        3
#define HAP_COMPUTE_RES_EVENT_CACHED_ACQUIRE_DONE                   4
#define HAP_COMPUTE_RES_EVENT_CACHED_RELEASE                        5
#define HAP_COMPUTE_RES_EVENT_CACHED_RELEASE_DONE                   6
#define HAP_COMPUTE_RES_EVENT_RELEASE                               7
#define HAP_COMPUTE_RES_EVENT_RELEASE_DONE                          8
#define HAP_COMPUTE_RES_EVENT_HMX_LOCK                              9
#define HAP_COMPUTE_RES_EVENT_HMX_LOCK_DONE                         10
#define HAP_COMPUTE_RES_EVENT_PREEMPT                               11
#define HAP_COMPUTE_RES_EVENT_PREEMPT_DONE                          12
#define HAP_COMPUTE_RES_EVENT_BACKUP                                13
#define HAP_COMPUTE_RES_EVENT_BACKUP_DONE                           14
#define HAP_COMPUTE_RES_EVENT_RESUME                                15
#define HAP_COMPUTE_RES_EVENT_RESUME_DONE                           16
#define HAP_COMPUTE_RES_EVENT_PREEMPT_HMX_CRIT                      17
#define HAP_COMPUTE_RES_EVENT_PREEMPT_HMX_CRIT_DONE                 18
#define HAP_COMPUTE_RES_EVENT_HMX_UNLOCK                            19
#define HAP_COMPUTE_RES_EVENT_HMX_UNLOCK_DONE                       20
#define HAP_COMPUTE_RES_EVENT_CACHED_YIELD                          21
#define HAP_COMPUTE_RES_EVENT_CACHED_YIELD_DONE                     22
/**
 * Masks returned by preemption capability query
 */
 #define HAP_COMPUTE_RES_UPDATE_CENG_HANDLE                          0x20000000U
/**< Mask indicating support for CENG aperture handle update under pre-emption
 *   capability query
 */
#define HAP_COMPUTE_RES_YIELD_API_PRESENT                           0x40000000U
/**< Mask indicating support for Yield API under cooperative pre-emption feature
 */
#define HAP_COMPUTE_RES_CENG_APERTURE_HANDLE                        0x80000000U
/**< Mask indicating support for CENG resource management under pre-emption
 *   capability query
 */

/**
 * Structure for event log item
 */
typedef struct {
    unsigned int eventId;
    /**< Event ID */
    unsigned int reserved_1;
    /**< Reserved 32-bit field */
    unsigned long long timestamp;
    /**< QTimer (19.2MHz) time stamp for the event */
    unsigned long long pCycles;
    /**< Processor cycle count */
    unsigned long long reserved_2[4];
} crm_event_log_t;

/**
 * @cond DEV
 */
int __attribute__((weak)) crm_set_profiling2(unsigned int version,
                                             compute_res_attr_t *attr,
                                             crm_event_log_t *event_buf,
                                             unsigned int size_event_log_t,
                                             unsigned short num_events,
                                             unsigned short log_level);

int __attribute__((weak)) crm_attr_set_ceng_handle(
                                        compute_res_attr_t* attr,
                                        unsigned int aperture_handle);

int __attribute__((weak)) compute_resource_yield_cached(
                                        unsigned int context_id,
                                        unsigned int timeout_us);

int __attribute__((weak)) compute_res_register_preemption_cb(void (*user_callback)(int command,
                                        void *preempt_va,
                                        unsigned int size,
                                        unsigned int grp_id));

int __attribute__((weak)) compute_res_deregister_preemption_cb(void (*user_callback)(int command,
                                        void *preempt_va,
                                        unsigned int size,
                                        unsigned int grp_id));

int __attribute__((weak)) crm_context_update_ceng_handle(
                                        unsigned int context_id,
                                        unsigned int aperture_handle);
/**
 * @endcond
 */

/**
 * @defgroup profiling Setup profiling
 * Sets up profiling with the Resource Manager.
 * @{
 */

/**
 * Set event log buffer in the attribute structure used for
 * requesting compute resources via HAP_compute_res_acquire().
 * The registered buffer will be updated in a circular fashion.
 * Timestamp can be used to order the circular buffer.
 * Client shall ensure that the buffer provided
 * is valid till HAP_compute_res_release() and is not modified
 * during this period.
 * @param[in] attr Pointer to the compute resource attribute structure,
 *                 #compute_res_attr_t.
 * @param[in/out] event_buf pointer to an array of #crm_event_log_t
 *                elements. The compute resource manager shall write into this
 *                buffer circularly with the event log.
 * @param[in] num_events number of #compute_res_event_log items allocated for
 *                       the provided #event_buf.
 * @param[in] log_level 0 - all events, 1 - default events. Used as a filter
 *                      for selecting events to be logged.
 */
static inline int HAP_compute_res_attr_set_profiling(
                                        compute_res_attr_t *attr,
                                        crm_event_log_t* event_buf,
                                        unsigned short num_events,
                                        unsigned short log_level)
{
    if (crm_set_profiling2)
    {
        return crm_set_profiling2(HAP_COMPUTE_RES_EXT_API_VERSION,
                                  attr, event_buf, sizeof(crm_event_log_t), num_events, log_level);
    }

    return HAP_COMPUTE_RES_NOT_SUPPORTED;
}

/*
 * Updates the CENG aperture handle for a cached compute resource context.
 * This function must be called before HAP_compute_res_acquire_cached()
 * on a context acquired via HAP_compute_res_acquire() with cached property set.
 * The provided CENG aperture handle will be allocated when
 * HAP_compute_res_acquire_cached() is called. The compute resource manager will
 * pre-empt the aperture handle when pre-empting the associated context.
 * The context provided must have the VTCM allocation request associated with the
 * CENG aperture handle.
 *
 * @param[in] context_id unsigned int Context ID returned by HAP_compute_res_acquire()
 *
 * @param[in] aperture_handle unsigned int ceng handle acquired from CENG driver.
 *
 * @return
 *      0 - success
 *      non-zero for failure
 *            HAP_COMPUTE_RES_NOT_SUPPORTED when API is not supported
 */
static inline int HAP_compute_res_update_ceng_handle(unsigned int context_id,
                                                        unsigned int aperture_handle)
{
    if (crm_context_update_ceng_handle)
    {
        return crm_context_update_ceng_handle(context_id, aperture_handle);
    }

    return HAP_COMPUTE_RES_NOT_SUPPORTED;
}

/*
 * Sets CENG aperture handle acquired from CENG driver in the attribute structure.
 * Registered CENG aperture handle is mapped during HAP_compute_res_acquire()
 * when cached property is not set or in HAP_compute_res_acquire_cached() when
 * cached property is set in the attribute structure.
 * The compute resource manager shall pre-empt the aperture handle while
 * pre-empting the associated context.
 * User shall also request for VTCM using the same attribute structure. The
 * HAP_compute_res_acquire() will return failure if VTCM is not requested
 * together with the CENG aperture handle.
 *
 * @param[in] attr Pointer to the compute resource attribute structure,
 *                 #compute_res_attr_t.
 * @param[in] aperture_handle unsigned int aperture handle acquired from CENG
 *                            driver.
 * @return
 *      0 - success
 *      non-zero for failure
 *            HAP_COMPUTE_RES_NOT_SUPPORTED when API is not supported
 */
static inline int HAP_compute_res_attr_set_ceng_handle(
                                        compute_res_attr_t *attr,
                                        unsigned int aperture_handle)
{
    if (crm_attr_set_ceng_handle)
    {
        return crm_attr_set_ceng_handle(attr, aperture_handle);
    }

    return HAP_COMPUTE_RES_NOT_SUPPORTED;
}

/*
 * Releases and re-acquires the provided context when a high priority client
 * is waiting for the resource.
 *
 * When a context is acquired by providing a release callback, the resource
 * manager triggers the registered callback when a high priority request
 * needs the resource. On receiving a release intimation via release callback,
 * Applications can use this API to yield the resource to the high priority
 * thread and wait for the resource to be available within the given timeout.
 *
 * Applications can use the acquired resources post a successful return of this
 * function.
 *
 * Supported starting with v81 architecture.
 *
 * @param[in] context_id Context ID returned by HAP_compute_res_acquire().
 *
 * @return
 * 0 if the provided context is successfully allocated. \n
 * non-zero up on failure. \n
 * HAP_COMPUTE_RES_NOT_SUPPORTED when this function is not supported. \n
 */
static inline int HAP_compute_res_yield_cached(unsigned int context_id,
                                               unsigned int timeout_us)
{
    if (compute_resource_yield_cached)
    {
        return compute_resource_yield_cached(context_id, timeout_us);
    }

    return HAP_COMPUTE_RES_NOT_SUPPORTED;
}

/*
  * Enables a user driver to register a user callback function to be invoked by the resource manager
  * when a context associated with VTCM allocation is being pre-empted / resumed from pre-emption
  * autonomously. Only 1 user callback can be registered from a user process on CDSP. Note that this
  * function is context agnostic and shall be used by drivers like UBWC-D which work on user provided
  * VTCM memory to get pre-emption notifications.
  *
  * @param[in] user_callback pointer to a user callback function that takes in following arguments:
  *                          integer command: 1 - pre-emption notification; 2 - resumption notification
  *                          void *preempt_va: Starting virtual address of the pre-empting/resuming
  *                                                           VTCM memory
  *                          unsigned int size: Size of the region pointed to by preempt_va
  *                          unsigned int grp_id: Thread group ID associated with the VTCM allocation
  *
  * @return
  *  0 on success, \n
  *  non-zero up on failure. \n
  *  HAP_COMPUTE_RES_NOT_SUPPORTED when this function is not supported. \n
  */
static inline int HAP_compute_res_register_preemption_cb(void (*user_callback)(int command,
                                        void *preempt_va,
                                        unsigned int size,
                                        unsigned int grp_id))
{
    if (compute_res_register_preemption_cb)
    {
        return  compute_res_register_preemption_cb(user_callback);
    }

    return HAP_COMPUTE_RES_NOT_SUPPORTED;
}

/*
  * Deregisters previously registered user callback function for VTCM pre-emption notifications.
  * To be called from the user driver when the callbacks are no longer needed.
  *
  * @param[in] user_callback pointer to the user callback function earlier provided to
  *                                             HAP_compute_res_register_preemption_cb()
  * @return
  *   0 on success, \n
  *   non-zero up on failure. \n
  *   HAP_COMPUTE_RES_NOT_SUPPORTED when this function is not supported. \n
  */

static inline int HAP_compute_res_deregister_preemption_cb(void (*user_callback)(int command,
                                        void *preempt_va,
                                        unsigned int size,
                                        unsigned int grp_id))
{
    if (compute_res_deregister_preemption_cb)
    {
        return  compute_res_deregister_preemption_cb(user_callback);
    }

    return HAP_COMPUTE_RES_NOT_SUPPORTED;
}

/**
 * @}
 */

#ifdef __cplusplus
}
#endif

#endif //HAP_COMPUTE_RES_EXT_H_
