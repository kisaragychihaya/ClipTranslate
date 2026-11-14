#ifndef HAP_PS_H
#define HAP_PS_H
/*==============================================================================
  Copyright (c) 2012-2019,2024 Qualcomm Technologies Incorporated.
  All Rights Reserved Qualcomm Technologies Proprietary

  Export of this technology or software is regulated by the U.S.
  Government. Diversion contrary to U.S. law prohibited.
==============================================================================*/

#include "AEEStdDef.h"
#include "HAP_process.h"

/**
 * Maximum allowed remote process name length
 */
#define PROCESS_NAME_LEN 56


/** @defgroup manage_dynamic_list Manage Dynamic List.
 *  @{
 */

typedef struct HAP_process HAP_process;
struct HAP_process {
   char name[PROCESS_NAME_LEN];
   int32 asid;
   int32 hlos_pid;
};

/**
 * Get list of active processes
 * @param[out] num_processes : Number of active processes
 * @param[out] processes     : Pointer to the list of processes
 * @return                     0 on success, valid non-zero error code on failure
 */
int HAP_get_process_list(uint32* num_processes, HAP_process** processes);

/**
 * Add new entry to process list
 * @param[in] process : Pointer to node to be added to the process list
 * @return              0 on success, valid non-zero error code on failure
 */
int HAP_add_to_process_list(HAP_process* process);

/**
 * Remove entry from process list
 * @param[in] hlos_pid : HLOS process ID of entry to be removed from the process list
 * @return               0 on success, valid non-zero error code on failure
 */
int HAP_remove_from_process_list(int hlos_pid);

/**
 * Set name of current process
 * @param[in] name : Name of process
 * @return           0 on success, valid non-zero error code on failure
 */
int HAP_set_process_name(char *name);

/**
 * API deprecated from SM8150 onwards.
 */
int HAP_thread_migrate(int tidQ);

/**
 * @}
 */


/** @defgroup early_wakeup Signal early wakeup
 *  @{
 */


/** Send signal to CPU for early wake up
 *
 *  Send signal to CPU for early wake up with approximate time to complete the job.
 *  This signal helps to reduce FastRPC latency.
 *
 *  Args:
 *		@param[in] tidQ          : QuRT thread id of a skel invoke thread. Use qurt_thread_get_id()
 *                                 to retrieve the thread ID.
 *		@param[in] earlyWakeTime : approximate time (in us) to complete job after sending the signal
 *  Returns: 0 on success, valid non-zero error code on failure
 */
int HAP_send_early_signal(uint32_t tidQ, uint32_t earlyWakeTime);

/**
 * API deprecated from Lahaina onwards. Use HAP_send_early_signal() instead
 */
int fastrpc_send_early_signal(uint32_t tidQ, uint32_t earlyWakeTime);

/**
 * @}
 */



/** @defgroup thread_priority_ceiling Enquire thread priority ceiling
 *  @{
 */


/** Return the ceiling thread priority for the current process
 *
 *  Return the thread priority ceiling for the current process. QuRT thread priorities
 *  run from 1 to 255, with 1 being the highest. Unprivileged user processes will
 *  have a ceiling priority of 64.
 *
 *  Args: None
 *  Returns: Thread priority ceiling value (bet 1 & 255) on success, -1 on failure
 */
int HAP_get_thread_priority_ceiling(void);

/**
 * Identifies the HAP request user pd parameters type
 * @param HAP_req_get_orig_apps_pid  : Returns the process original apps pid.
 */
typedef enum {
   HAP_req_get_orig_apps_pid = 1,
} HAP_req_userpd_params_type;

/**
 * Data type to get requested value from the DSP
 * @param type          : Identifies the request type.
 * @param orig_apps_pid : Returns the process original apps pid.
 */
typedef struct {
   HAP_req_userpd_params_type type;
   union {
      int orig_apps_pid;
   };
} HAP_req_userpd_params_t;

/**
 * Method to retrieve user process values from the DSP. This API support from SM8750 onwards.
 * @param [in] request  : Request params.
 * @return Returns 0 for success, error code on failure.
 */
int HAP_get_userpd_params(HAP_req_userpd_params_t *request);

/**
 * @}
 */

/** @defgroup HAP_get_pd_type Query the PD type of the process
 *  @{
 */


/** Return PD type of the spawned process
 *  @param[out] pd_type : Pointer to enum process_type to get PD type
 *  @return 0 on success, valid non-zero error code on failure
 */
int HAP_get_pd_type(enum process_type* pd_type);

/**
 * @}
 */

/**
 * @defgroup HAP_session_request: Query for session related information
 * @{
 */

/** Enum for HAP_session_request ids */
typedef enum {
	/** Get range of exclusive priorities for calling thread's group */
    HAP_session_get_prio_range = 1,

	/** Get logical id of current session in a multidomain context */
    HAP_session_get_multidomain_logical_id,
} HAP_session_request_type;

/** Payload for 'HAP_session_get_prio_range' request */
typedef struct {
	/**< @param[out] : Priority ceiling of thread group. This is different from the
					   normal ceiling priority that is returned by HAP_get_thread_priority_ceiling
					   which represents the ceiling priority for the entire user process. */
    unsigned int ceiling;

	/**< @param[out] : Lowest exclusive priority of thread group. Priorities lower than
					   this can be used by thread-groups below caller's thread-group. */
    unsigned int lowest_excl;
} HAP_session_excl_prio_range;

/** Payload for 'HAP_session_get_multidomain_logical_id' request */
typedef struct {
	/** @param[in] : Multidomain context id */
    uint64_t ctx;

	/** @param[out] : Logical id of current id in multidomain context. The same session can have
					  different logical ids in different multidomain contexts. */
    int id;
} HAP_session_multidomain_logical_id;

/** Payload for HAP_session_request */
typedef struct {
	/** Request id */
    HAP_session_request_type type;
    union {
		/** Query exclusive thread priority range of calling thread's group */
        HAP_session_excl_prio_range prio;

		/** Query logical id of current session in multidomain context */
        HAP_session_multidomain_logical_id mdq;
    };
} HAP_session_request_t;

/**
 *  HAP_session_request
 *  Function to get information about the session.
 *  @param [in/out]: Pointer to HAP_session_request_t structure containing
 *                   request type and payload structure
 */
int HAP_session_request(HAP_session_request_t* request);

/**
 * @}
 */

#endif /*HAP_PS_H */

