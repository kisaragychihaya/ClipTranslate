/*
  Copyright (c) 2020 Qualcomm Technologies, Inc.
  All Rights Reserved.
  Confidential and Proprietary - Qualcomm Technologies, Inc.
*/


/** @file
    Asynchronous DSP Packet Queue API.
*/

#ifndef DSPQUEUE_H
#define DSPQUEUE_H

#include <stdlib.h>
#include <stdint.h>
#include <stdbool.h>
#include <AEEStdDef.h>


/** @defgroup dspqueue_consts Asynchronous DSP Packet Queue API Constants
 *  @{
 */

/** Infinite timeout */
#define DSPQUEUE_TIMEOUT_NONE 0xffffffff


/**
 * Packet flags. The flags are used as a bitfield in packet read/write operations.
 */
enum dspqueue_packet_flags {
    DSPQUEUE_PACKET_FLAG_MESSAGE =       0x0001, /**< Packet contains a message */
    DSPQUEUE_PACKET_FLAG_BUFFERS =       0x0002, /**< Packet contains buffer references */
    DSPQUEUE_PACKET_FLAG_WAKEUP =        0x0004, /**< Early wakeup packet */
    DSPQUEUE_PACKET_FLAG_DRIVER_READY =  0x0008, /**< Packet is ready for driver consumption. Currently unused. */
    DSPQUEUE_PACKET_FLAG_USER_READY =    0x0010, /**< Packet is ready for userspace library consumption */
    DSPQUEUE_PACKET_FLAG_FRAMEWORK  =    0x0020, /**< Packet is a framework packet */
    DSPQUEUE_PACKET_FLAG_RESERVED_ZERO = (0xFFFF & ~((DSPQUEUE_PACKET_FLAG_FRAMEWORK << 1) - 1))
};

/**
 * Buffer flags. The flags are used in dspqueue_buffer.flags as a bitfield.
 */
enum dspqueue_buffer_flags {
    /* 1 and 2 reserved */
    DSPQUEUE_BUFFER_FLAG_REF =                  0x00000004, /**< Add a reference to a previously mapped buffer */
    DSPQUEUE_BUFFER_FLAG_DEREF =                0x00000008, /**< Remove a reference from a previously mapped buffer */
    DSPQUEUE_BUFFER_FLAG_FLUSH_SENDER =         0x00000010, /**< Flush buffer from sender caches */
    DSPQUEUE_BUFFER_FLAG_INVALIDATE_SENDER =    0x00000020, /**< Invalidate buffer from sender caches */
    DSPQUEUE_BUFFER_FLAG_FLUSH_RECIPIENT =      0x00000040, /**< Flush buffer from recipient caches */
    DSPQUEUE_BUFFER_FLAG_INVALIDATE_RECIPIENT = 0x00000080, /**< Invalidate buffer from recipient caches */
    DSPQUEUE_BUFFER_FLAG_RESERVED_ZERO =        0xffffff00
};


/**
 * Statistics readable with dspqueue_get_stat()
 */
enum dspqueue_stat {
    DSPQUEUE_STAT_READ_QUEUE_PACKETS = 1, /**< Numbers of packets in the read queue */
    DSPQUEUE_STAT_READ_QUEUE_BYTES,       /**< Number of bytes in the read queue */
    DSPQUEUE_STAT_WRITE_QUEUE_PACKETS,    /**< Number of packets in the write queue */
    DSPQUEUE_STAT_WRITE_QUEUE_BYTES,      /**< Number of bytes in the write queue */

    DSPQUEUE_STAT_EARLY_WAKEUP_WAIT_TIME, /**< Total accumulated early wakeup wait time in microseconds.
                                             Developers can use this value to tune their early wakeup
                                             request timing; the target should be to have this value as
                                             close to zero as possible while minimizing signaling latency.
                                             For more information on tuning early wakeup requests, see the
                                             "Performance Considerations" section in the main Hexagon SDK
                                             "Asynchronous Packet Queue" document. */

    DSPQUEUE_STAT_EARLY_WAKEUP_MISSES,    /**< Number accumulated of packets missed in the early wakeup loop.
                                             Developers can use this value to tune their early wakeup
                                             request timing. If this value is above zero it indicates the
                                             early wakeup request was sent too early and it expired before
                                             the corresponding packet was received.
                                             For more information on tuning early wakeup requests, see the
                                             "Performance Considerations" section in the main Hexagon SDK
                                             "Asynchronous Packet Queue" document. */
    DSPQUEUE_STAT_SIGNALING_PERF,         /**< Signaling performance; 0 or undefined indicates the first implementation,
                                               DSPQUEUE_SIGNALING_PERF_REDUCED_SIGNALING or higher a version if reduced
                                               signaling for polling clients. */
};

/**
 * Type of dspqueue events.
 * Events are used for synchronization between dspqueues.
 */
typedef enum {
	/**
	 * Event can be used to sync only between discrete subsystems.
	 * The host CPU cannot use this event. It is intended for
	 * dsp-to-dsp synchronization.
	 */
	DSPQUEUE_EVENT_DISCRETE_ONLY,

	/** Maximum event type value for validation */
	DSPQUEUE_EVENT_TYPE_MAX,
} dspqueue_event_type;

/**
 * Type of dspqueue event packet.
 * Event packets control synchronization behavior in queues.
 */
typedef enum {
	/**
	 * Event wait packet.
	 * The queue to which the packet is written to, will wait for the
	 * event to be recorded (by another queue), before proceeding to read
	 * subsequent packets.
	 */
	DSPQUEUE_EVENT_PACKET_WAIT,

	/**
	 * Event record packet.
	 * The queue to which the packet is written to, will record the event
	 * before proceeding to read subsequent packets.
	 */
	DSPQUEUE_EVENT_PACKET_RECORD,

	/** Maximum event packet type value for validation */
	DSPQUEUE_EVENT_PACKET_TYPE_MAX,
} dspqueue_event_packet_type;

/**
 * dspqueue creation flags.
 * Flags are defined as a bitmask,
 * allowing multiple options to be combined.
 */
enum dspqueue_create_flags {
	/** Create inter-domain queue */
	DSPQUEUE_CREATE_INTERDOMAIN = 0x1,

	/** Future flags should continue at 0x2, 0x4 etc */
};

/**
 * Request ids to be used with dspqueue_request()
 */
typedef enum {
	DSPQUEUE_CREATE,      /**< Create a multi-domain dspqueue i.e. a queue which has one or more
                             * DSP endpoints. Any packet written to the queue will be delivered
                             * to each endpoint and a signal will be sent to all waiting threads
                             * on each endpoint.
                             * Multi-domain queue can either be created on multiple sessions on
                             * the same domain or one session each on multiple domains.
                             */

	DSPQUEUE_ASYNC_MEMCPY,        /**< Write an async mem-copy request to a single-domain dspqueue */

	DSPQUEUE_EVENT_CREATE,        /**< Create new dspqueue event */

	DSPQUEUE_EVENT_DESTROY,       /**< Destroy dspqueue event */

	DSPQUEUE_EVENT_PACKET_WRITE,  /**< Write dspqueue event packet (to wait on / record event) */

	DSPQUEUE_SET_APPID,     /**< Set or update the appid on an existing queue.
                               * All queues are created with appid defaulting to the current
                               * app's appid. Use this request to override with the true client
                               * application appid after creation — important when FastRPC is
                               * driven through shared or proxy processes where the visible
                               * appid may not match the actual calling app.
                               * Must be called before each remote_work_control() START if
                               * the calling context changes between submissions.
                               * Pass appid = 0 to reset the queue's appid back to the
                               * current app's appid.
                               */

	DSPQUEUE_REQUEST_MAX,   /**< Max dspqueue request id */
} dspqueue_request_req_id;

/** Signaling performance level: Reduced signaling for polling clients. */
#define DSPQUEUE_SIGNALING_PERF_REDUCED_SIGNALING 100

/** Signaling performance level: Optimized signaling for all clients. */
#define DSPQUEUE_SIGNALING_PERF_OPTIMIZED_SIGNALING 1000

/** @}
 */


/** @defgroup dspqueue_types Asynchronous DSP Packet Queue API Data Types
 *  @{
 */

struct dspqueue;
typedef struct dspqueue* dspqueue_t; /**< Queue handle */
typedef struct dspqueue_event* dspqueue_event_t; /**< Event handle */


/**
 * Buffer reference in a packet.
 * The buffer must already be mapped to the DSP using the same file descriptor.
 * The subsection of the buffer as specified by #offset and #size must fit
 * entirely within the mapped buffer.
 * Note that buffer references are tracked based on the buffer file descriptor,
 * and taking/releasing a reference to a buffer applies to the entire buffer as
 * mapped to the DSP, not just the subsection specified.
 */
struct dspqueue_buffer {
    uint32_t fd;     /**< Buffer file descriptor */
    uint32_t size;   /**< Buffer size in bytes. The client can set this field
                          to zero when writing packets; in this case the
                          framework will set the field to the size of the
                          buffer as mapped. */
    uint32_t offset; /**< Offset within the buffer in bytes as allocated and mapped.
                          The virtual address #ptr includes the offset */
    uint32_t flags;  /**< Buffer flags, see enum #dspqueue_buffer_flags */
    union {
        void *ptr;   /**< Buffer virtual address; NULL if not mapped in the local context */
        uint64_t address;
    };
};


/**
 * Callback function type for all queue callbacks
 *
 * @param queue Queue handle from dspqueue_create() / dspqueue_import()
 * @param error Error code
 * @param context Client-provided context pointer
 */
typedef void (*dspqueue_callback_t)(dspqueue_t queue, AEEResult error, void *context);

/**
 * Callback function type for all queue callbacks
 *
 * @param queue Queue handle from dspqueue_create() / dspqueue_import()
 * @param error Error code
 * @param context Client-provided context pointer
 * @param logical_id      Logical id of domain in context which triggered
 *                        callback
 * @param effec_domain_id Effective domain id of remote session which
 *                        triggered callback
 */
typedef void (*dspqueue_mdq_callback_t)(dspqueue_t queue, AEEResult error,
	void *context, int logical_id, int effec_domain_id);

/**
 * Struct to be used with DSPQUEUE_CREATE request
 */
typedef struct dspqueue_create_req {
	/** @param[in] : Fastrpc multi-domain context.
					 In case of inter-domain queues, this context should've been created
					 on exactly TWO effective domain ids. */
	uint64_t ctx;

	/** @param[in] : Queue creation flags. Refer enum dspqueue_create_flags */
	uint64_t flags;

	/** @param[in] : Request queue memory size (in bytes).
					 In case of multi-domain queue, for each remote session, one
					 request-queue of this size will be created and mapped i.e. if there
					 are N domains provided, there will be N request queues created.
					 In case of inter-domain queue, a single request queue will be created
					 where the first remote-session in the context will write to and the
					 second remote-session in the context will read from.
					 Use 0 for system default */
	uint32_t req_queue_size;

	/** @param[in] : Response queue memory size (in bytes).
					 In case of multi-domain queue, for each remote session, one
					 response-queue of this size will be created and mapped i.e. if there
					 are N domains provided, there will be N response queues created.
					 In case of inter-domain queue, a single response queue will be
					 created where the second remote-session in the context will write to
					 and the first remote-session in the context will read from.
					 Use 0 for system default */
	uint32_t resp_queue_size;

	/** @param[in] : Queue priority (unused for now) */
	uint32_t priority;

	/** @param[in] : Callback function invoked when there are new packets to read.
					 When there are response packets from multiple domains on the queue,
					 this callback can be called concurrently from multiple threads.
					 Client is expected to consume each available response packet.
					 Must be NULL for inter-domain queue creation.
					 NULL to disable. */
	dspqueue_mdq_callback_t packet_callback;

	/** @param[in] : Callback function called on unrecoverable errors.
					 NULL to disable. */
	dspqueue_mdq_callback_t error_callback;

	/** @param[in] : Context pointer for callback functions */
	void *callback_context;

	/** @param[out] : Queue handle */
	dspqueue_t queue;

	/** @param[out] : Queue ID array. Needs to be allocated by caller.
					  Array will be populated with queue IDs of each domain.
					  Size of array should be same as number of domains on which
					  multi-domain context was created.
					  Use corresponding queue id to import the queue on each remote
					  session. */
	uint64_t *ids;

	/** @param[in] : Size of queue IDs array (must be same as number of domains) */
	uint32_t num_ids;
} dspqueue_create_req;

/**
 * Payload structure for DSPQUEUE_ASYNC_MEMCPY request
 * to post an asynchronous mem-copy request on a single-domain dspqueue.
 */
typedef struct {
	/** @param[in] : Queue handle */
	dspqueue_t queue;

	/** @param[in] : If set to true, the api will not block when the queue is full.
					 Instead, it will immediately return a failure status without
					 submitting the async mem-copy request.
					 If false, the API will wait until space becomes available in the
					 queue before submitting the request.
					 Note: This does not affect the async nature of the mem-copy itself. */
	bool noblock;

	/** @param[in] : Timeout in microseconds to wait for space in the queue
					 if it is full. Use DSPQUEUE_TIMEOUT_NONE to block indefinitely.
					 This field is ignored if 'noblock' is set to true. */
	uint32_t timeout_us;

	/** @param[in] : Fd to source buffer */
	int src_fd;

	/** @param[in] : Fd to destination buffer */
	int dst_fd;

	/** @param[in] : Offset (in bytes) in source buffer */
	size_t src_offset;

	/** @param[in] : Offset (in bytes) in destination buffer */
	size_t dst_offset;

	/** @param[in] : Size from offset (in bytes) in source buffer to be copied.
					 Copy will be done to same offset in destination buffer. */
	size_t size;
} dspqueue_async_memcpy_payload;

/**
 * Payload structure for DSPQUEUE_EVENT_CREATE request
 * to create a dspqueue event.
 */
typedef struct {
	/** @param[in] : Type of dspqueue event to be created */
	dspqueue_event_type type;

	/** @param[in] : Remote session to be used to setup the event.
					 After creation, the event can still be used on any other remote
					 session. */
	uint32_t effec_domain_id;

	/** @param[out] : dspqueue event object */
	dspqueue_event_t event;

	/** @param[in] : Reserved for future use */
	uint64_t flags;
} dspqueue_event_create_payload;

/**
 * Payload structure for DSPQUEUE_EVENT_DESTROY request
 * to destroy a dspqueue event.
 */
typedef struct {
	/** @param[in] : dspqueue event object */
	dspqueue_event_t event;

	/** @param[in] : Reserved for future use */
	uint64_t flags;
} dspqueue_event_destroy_payload;

/**
 * Payload to be used with DSPQUEUE_EVENT_PACKET_WRITE request
 * to write an event wait / record packet to a queue.
 */
typedef struct {
	/** @param[in] : Queue to write event packet to */
	dspqueue_t queue;

	/** @param[in] : If set to true, the api will not block when the queue is full.
					 Instead, it will immediately return a failure status without
					 submitting the event request.
					 If false, the API will wait until space becomes available in the
					 queue before submitting the request.
					 Note: This does not affect the actual event-wait itself. */
	bool noblock;

	/** @param[in] : Timeout in microseconds to wait for space in the queue if it is
					 full. Use DSPQUEUE_TIMEOUT_NONE to block indefinitely.
					 This field is ignored if 'noblock' is set to true. */
	uint32_t pkt_timeout;

	/** @param[in] : dspqueue event object */
	dspqueue_event_t event;

	/** @param[in] : Type of event packet */
	dspqueue_event_packet_type type;

	/** @param[in] : Event wait timeout in microseconds.
					 Valid input only for DSPQUEUE_EVENT_PACKET_WAIT type.
					 When the event-wait packet is encountered, the queue will wait
					 for this timeout for the event to be recorded.
					 If wait times out, error callback will be invoked on host and
					 queue will enter error-state.
					 Use DSPQUEUE_TIMEOUT_NONE to wait for event indefinitely. */
	uint32_t event_timeout;

	/** @param[in] : Reserved for future use */
	uint64_t flags;
} dspqueue_event_packet_payload;

/**
 * Struct to be used with DSPQUEUE_SET_APPID request.
 * Sets or updates the appid on an existing queue so the NPU scheduler can
 * associate it with the correct client workload.
 */
typedef struct dspqueue_set_appid_req {
    /** @param[in] : Handle of the queue to update, from dspqueue_request(DSPQUEUE_CREATE) */
    dspqueue_t queue;

    /**
     * @param[in] : appid of the originating client application.
     *
     * Used by the NPU scheduler to identify the workload owner, especially
     * when FastRPC calls are made via shared or proxy processes where the
     * visible UID may not correspond to the actual calling application.
     *
     * Pass 0 to reset the queue's appid to getuid().
     */
    uint32_t appid;
} dspqueue_set_appid_req;

/**
 * Payload for dspqueue_request()
 */
typedef struct dspqueue_request_payload {
	/** @param[in] : Request id */
	dspqueue_request_req_id id;
	union {
		dspqueue_create_req create;                    /**< Create a multidomain dspqueue */
		dspqueue_async_memcpy_payload async_copy;      /**< Async mem-copy request */
		dspqueue_event_create_payload event_create;    /**< Create dspqueue event */
		dspqueue_event_destroy_payload destroy;        /**< Destroy dspqueue event */
		dspqueue_event_packet_payload event_pkt;       /**< Write event packet to queue */
		dspqueue_set_appid_req set_appid;              /**< Set appid on an existing queue */
	};
} dspqueue_request_payload;

/** @}
 */


#ifdef __cplusplus
extern "C" {
#endif


/** @defgroup dspqueue_funcs Asynchronous DSP Packet Queue API Functions
 *  @{
 */

/**
 * Create a new queue to communicate with the DSP. Queues can only be
 * created on the host CPU.
 *
 * This function cannot be used to create multi-domain queue.
 * Refer 'dspqueue_request' for that.
 *
 * @param [in] domain DSP to communicate with (CDSP_DOMAIN_ID in remote.h for cDSP)
 * @param [in] flags Queue creation flags
 * @param [in] req_queue_size Total request queue memory size in bytes; use 0 for system default
 * @param [in] resp_queue_size Total response queue memory size in bytes; use 0 for system default
 * @param [in] packet_callback Callback function called when there are new packets to read.
 *                        The call will be done in a different thread's context.
 *                        NULL to disable the callback. Clients cannot use blocking read
 *                        calls if a packet callback has been set.
 * @param [in] error_callback Callback function called on unrecoverable errors. NULL to disable.
 * @param [in] callback_context Context pointer for callback functions
 * @param [out] queue Queue handle
 *
 * @return 0 on success, error code on failure.
 *         - AEE_ENOMEMORY: Not enough memory available
 *         - AEE_EUNSUPPORTED: Message queue not supported on the given DSP
 *         - AEE_EBADPARM: Bad parameters, e.g. Invalid domain (use CDSP_DOMAIN_ID for cDSP), Too many queues open for the DSP in this process
 *         - AEE_ERPC: Internal RPC error, e.g. Queue list corrupt
 *         - AEE_EBADSTATE: Bad internal state
 */
AEEResult dspqueue_create(int domain,
                          uint32_t flags,
                          uint32_t req_queue_size, uint32_t resp_queue_size,
                          dspqueue_callback_t packet_callback,
                          dspqueue_callback_t error_callback,
                          void *callback_context,
                          dspqueue_t *queue);

/**
 * Close a queue and free all memory associated with it. The
 * function can be called on the host CPU with queue handles from
 * dspqueue_create() or on the DSP with handles from
 * dspqueue_import().
 *
 * This function can be called on both single-domain and multi-domain
 * queues.
 *
 * @param [in] queue Queue handle from dsp_queue_create() from dsp_queue_import().
 *
 * @return 0 on success, error code on failure.
 *         - AEE_EBADPARM: Bad parameters, e.g. The queue is open on the DSP when attempting to close it on the host CPU
 *         - AEE_EBADSTATE: Bad internal state
 */
AEEResult dspqueue_close(dspqueue_t queue);

/**
 * Export a queue to the DSP. The CPU-side client calls this function,
 * passes the ID to the DSP, which can then call dspqueue_import() to
 * access the queue.
 *
 *
 * This function is not required to be called on multi-domain queues.
 *
 * @param [in] queue Queue handle from dspqueue_create()
 * @param [out] queue_id Queue ID
 *
 * @return 0 on success, error code on failure.
 */
AEEResult dspqueue_export(dspqueue_t queue, uint64_t *queue_id);

/**
 * Import a queue on the DSP based on an ID passed in from the host
 * CPU. The DSP client can use the returned queue handle to access the
 * queue and communicate with its host CPU counterpart.
 *
 * @param [in] queue_id Queue ID from dspqueue_export().
 * @param [in] packet_callback Callback function called when there are new packets to read.
 *                        The call will be done in a different thread's context.
 *                        NULL to disable the callback.
 * @param [in] error_callback Callback function called on unrecoverable errors. NULL to disable.
 * @param [in] callback_context Context pointer fo callback functions
 * @param [out] queue Queue handle
 *
 * @return 0 on success, error code on failure.
 *         - AEE_EITEMBUSY: The queue has already been imported
 *         - AEE_EQURTTHREADCREATE: Unable to create callback thread; the system may have
 *                                  reached its thread limit.
 *         - AEE_EBADSTATE: Bad internal state
 */
AEEResult dspqueue_import(uint64_t queue_id,
                          dspqueue_callback_t packet_callback,
                          dspqueue_callback_t error_callback,
                          void *callback_context,
                          dspqueue_t *queue);

/**
 * Make dspqueue related requests - like creation of multi-domain queue
 *
 * @param [in] req : Request payload
 *
 * @return 0 on success, error code on failure.
 *			- AEE_ENOMEMORY    : Not enough memory available
 *			- AEE_EUNSUPPORTED : Message queue not supported on the given domains
 *			- AEE_EBADPARM     : Bad parameters, e.g. invalid context
 *			- AEE_ERPC         : Internal RPC error
 */
int dspqueue_request(dspqueue_request_payload *req);

/**
 * Write a packet to a queue. This variant of the function will not
 * block, and will instead return AEE_EWOULDBLOCK if the queue does not have
 * enough space for the packet.
 *
 * With this function the client can pass separate pointers to the
 * buffer references and message to include in the packet and the
 * library copies the contents directly to the queue.
 *
 * When this is called on a multi-domain queue, the packet will be shared
 * with all remote domains the queue was created on.
 * If any of the domains is unable to receive the packet, it means the
 * queue is in a bad-state and is no longer usable. Client is expected to
 * close the queue and reopen a new one.
 *
 * @param [in] queue Queue handle from dspqueue_create() or dspqueue_import()
 * @param [in] flags Packet flags. See enum #dspqueue_packet_flags
 * @param [in] num_buffers Number of buffer references to insert to the packet;
 *                    zero if there are no buffer references
 * @param [in] buffers Pointer to buffer references
 * @param [in] message_length Message length in bytes;
 *                       zero if the packet contains no message
 * @param [in] message Pointer to packet message
 *
 * @return 0 on success, error code on failure.
 *         - AEE_EWOULDBLOCK: The queue is full
 *         - AEE_EBADPARM: Bad parameters, e.g. buffers is NULL when num_buffers > 0 ,
 *                          The packet is too long to fit in the queue. The call will never succeed.
 *         - AEE_ENOSUCHMAP: Attempt to refer to an unmapped buffer. Buffers must be mapped to the DSP
 *                           with fastrpc_mmap() before they can be used in queue packets.
 *         - AEE_EBADSTATE: Bad internal state
 *         - AEE_ENOTALLOWED: write rejected - NPU scheduler enabled but remote work not started
 */
AEEResult dspqueue_write_noblock(dspqueue_t queue, uint32_t flags,
                                 uint32_t num_buffers, struct dspqueue_buffer *buffers,
                                 uint32_t message_length, const uint8_t *message);

/**
 * Write a packet to a queue. If the queue is full this function will
 * block until space becomes available or the request times out.
 *
 * With this function the client can pass separate pointers to the
 * buffer references and message to include in the packet and the
 * library copies the contents directly to the queue.
 *
 * When this is called on a multi-domain queue, the packet will be shared
 * with all remote domains the queue was created on. This call will block
 * (for specified timeout or indefinitely) until the packet is shared with
 * all domains.
 * If any of the domains is unable to receive the packet, it means the
 * queue is in a bad-state and is no longer usable. Client is expected to
 * close the queue and reopen a new one.
 *
 * @param [in] queue Queue handle from dspqueue_create() or dspqueue_import()
 * @param [in] flags Packet flags. See enum #dspqueue_packet_flags
 * @param [in] num_buffers Number of buffer references to insert to the packet;
 *                    zero if there are no buffer references
 * @param [in] buffers Pointer to buffer references
 * @param [in] message_length Message length in bytes;
 *                       zero if the packet contains no message
 * @param [in] message Pointer to packet message
 * @param [in] timeout_us Timeout in microseconds; use DSPQUEUE_TIMEOUT_NONE to
 *                   block indefinitely until a space is available or
 *                   zero for non-blocking behavior.
 *
 * @return 0 on success, error code on failure.
 *         - AEE_EBADPARM: Bad parameters, e.g. buffers is NULL when num_buffers > 0
 *                         The packet is too long to fit in the queue. The call will never succeed.
 *         - AEE_ENOSUCHMAP: Attempt to refer to an unmapped buffer. Buffers must be mapped to the DSP
 *                           with fastrpc_mmap() before they can be used in queue packets.
 *         - AEE_EEXPIRED: Request timed out
 *         - AEE_EINTERRUPTED: The request was canceled
 *         - AEE_EBADSTATE: Bad internal state
 *         - AEE_ENOTALLOWED: write rejected - NPU scheduler enabled but remote work not started.
 */
AEEResult dspqueue_write(dspqueue_t queue, uint32_t flags,
                         uint32_t num_buffers, struct dspqueue_buffer *buffers,
                         uint32_t message_length, const uint8_t *message,
                         uint32_t timeout_us);

/**
 * Read a packet from a queue. This variant of the function will not
 * block, and will instead return AEE_EWOULDBLOCK if the queue does not have
 * enough space for the packet.
 *
 * This function will read packet contents directly into
 * client-provided buffers. The buffers must be large enough to fit
 * contents from the packet or the call will fail.
 *
 * When this is called on a multi-domain queue, it will return the
 * response packet from the first domain where it finds one. If multiple
 * domains have posted a response to the multi-domain queue, the client is
 * expected to call this function as many times to consume the response
 * packet from all domains.
 *
 * @param [in] queue Queue handle from dspqueue_create() or dspqueue_import()
 * @param [out] flags Packet flags. See enum #dspqueue_packet_flags
 * @param [in] max_buffers The maximum number of buffer references that can fit in the "buffers" parameter
 * @param [out] num_buffers The number of buffer references in the packet
 * @param [out] buffers Buffer reference data from the packet
 * @param [in] max_message_length Maximum message length that can fit in the "message" parameter
 * @param [out] message_length Message length in bytes
 * @param [out] message Packet message
 *
 * @return 0 on success, error code on failure.
 *         - AEE_EBADPARM: Bad parameters, e.g. The packet is too large to fit in the provided buffers
 *         - AEE_ENOSUCHMAP: The packet refers to an unmapped buffer. Buffers must be mapped to the DSP
 *                           with fastrpc_mmap() before they can be used in queue packets.
 *         - AEE_EWOULDBLOCK: The queue is empty; try again later
 *         - AEE_EBADITEM: The queue contains a corrupted packet. Internal error.
 *         - AEE_EBADSTATE: Bad internal state
 *         - AEE_ENOTALLOWED: read rejected - NPU scheduler enabled but remote work not started
 */
AEEResult dspqueue_read_noblock(dspqueue_t queue, uint32_t *flags,
                                uint32_t max_buffers, uint32_t *num_buffers, struct dspqueue_buffer *buffers,
                                uint32_t max_message_length, uint32_t *message_length, uint8_t *message);

/**
 * Read a packet from a queue. If the queue is empty this function
 * will block until a packet is available or the request times out.
 * The queue must not have a packet callback set.
 *
 * This function will read packet contents directly into
 * client-provided buffers. The buffers must be large enough to fit
 * contents from the packet or the call will fail.
 *
 * This function is currently not supported on multi-domain queues.
 *
 * @param [in] queue Queue handle from dspqueue_create() or dspqueue_import()
 * @param [out] flags Packet flags. See enum #dspqueue_packet_flags
 * @param [in] max_buffers The maximum number of buffer references that can fit in the "buffers" parameter
 * @param [out] num_buffers The number of buffer references in the packet
 * @param [out] buffers Buffer reference data from the packet
 * @param [in] max_message_length Maximum message length that can fit in the "message" parameter
 * @param [out] message_length Message length in bytes
 * @param [out] message Packet message
 * @param [in] timeout_us Timeout in microseconds; use DSPQUEUE_TIMEOUT_NONE to
 *                   block indefinitely until a packet is available or
 *                   zero for non-blocking behavior.
 *
 * @return 0 on success, error code on failure.
 *         - AEE_EBADPARM: Bad parameters, e.g. The packet is too large to fit in the provided buffers
 *         - AEE_ENOSUCHMAP: The packet refers to an unmapped buffer. Buffers must be mapped to the DSP
 *                           with fastrpc_mmap() before they can be used in queue packets.
 *         - AEE_EBADITEM: The queue contains a corrupted packet. Internal error.
 *         - AEE_EBADSTATE: Bad internal state
 *         - AEE_EEXPIRED: Request timed out
 *         - AEE_EINTERRUPTED: The request was canceled
  *        - AEE_ENOTALLOWED: read rejected - NPU scheduler enabled but remote work not started
 */
AEEResult dspqueue_read(dspqueue_t queue, uint32_t *flags,
                        uint32_t max_buffers, uint32_t *num_buffers, struct dspqueue_buffer *buffers,
                        uint32_t max_message_length, uint32_t *message_length, uint8_t *message,
                        uint32_t timeout_us);

/**
 * Retrieve information for the next packet if available, without reading
 * it from the queue and advancing the read pointer. This function
 * will not block, but will instead return an error if the queue is
 * empty.
 *
 * When this is called on a multi-domain queue, it will return the
 * response packet info from the first domain where it finds one. If
 * multiple domains have posted a response to the multi-domain queue, the
 * client is expected to consume a peeked packet first before attempting
 * to peek the next available packet from any of the domains.
 *
 * @param [in] queue Queue handle from dspqueue_create() or dspqueue_import().
 * @param [out] flags Packet flags. See enum #dspqueue_packet_flags
 * @param [out] num_buffers Number of buffer references in packet
 * @param [out] message_length Packet message length in bytes
 *
 * @return 0 on success, error code on failure.
 *         - AEE_EWOULDBLOCK: The queue is empty; try again later
 *         - AEE_EBADITEM: The queue contains a corrupted packet. Internal error.
 *         - AEE_EBADSTATE: Bad internal state
 *         - AEE_ENOTALLOWED: peek rejected - NPU scheduler enabled but remote work not started
 */
AEEResult dspqueue_peek_noblock(dspqueue_t queue, uint32_t *flags, uint32_t *num_buffers,
                                uint32_t *message_length);

/**
 * Retrieve information for the next packet, without reading it from the
 * queue and advancing the read pointer. If the queue is empty this
 * function will block until a packet is available or the request
 * times out.
 *
 * This function is currently not supported on multi-domain queues.
 *
 * @param [in] queue Queue handle from dspqueue_create() or dspqueue_import().
 * @param [out] flags Packet flags. See enum #dspqueue_packet_flags
 * @param [out] num_buffers Number of buffer references in packet
 * @param [out] message_length Packet message length in bytes
 * @param [out] timeout_us Timeout in microseconds; use DSPQUEUE_TIMEOUT_NONE to
 *                   block indefinitely until a packet is available or
 *                   zero for non-blocking behavior.
 *
 * @return 0 on success, error code on failure.
 *         - AEE_EEXPIRED: Request timed out
 *         - AEE_EINTERRUPTED: The request was canceled
 *         - AEE_EBADITEM: The queue contains a corrupted packet. Internal error.
 *         - AEE_EBADSTATE: Bad internal state
 *         - AEE_ENOTALLOWED: peek rejected - NPU scheduler enabled but remote work not started
 */
AEEResult dspqueue_peek(dspqueue_t queue, uint32_t *flags, uint32_t *num_buffers,
                        uint32_t *message_length, uint32_t timeout_us);


/**
 * Write an early wakeup packet to the queue. Early wakeup packets are used
 * to bring the recipient out of a low-power state in anticipation of a real
 * message packet being availble shortly, and are typically used from the DSP
 * to signal that an operation is almost complete.
 *
 * This function will return immediately if the queue is full. There is no
 * blocking variant of this function; if the queue is full the other endpoint
 * should already be processing data and an early wakeup would not be useful.
 *
 * When this function is called on a multi-domain queue, early wakeup
 * is done on all the domains that the queue was created on.
 *
 * @param [in] queue Queue handle from dspqueue_create() or dspqueue_import()
 * @param [in] wakeup_delay Wakeup time in microseconds; this indicates how soon
 *                     the real message packet should be available. Zero if not known.
 *                     The recipient can use this information to determine how to
 *                     wait for the packet.
 * @param [in] packet_flags Flags for the upcoming packet if known.
 *                    The framework can use this information to optimize its
 *                    behavior if the flags match the upcoming packet; if not known
 *                    set to zero.
 *                    See enum #dspqueue_packet_flags
 *
 * @return 0 on success, error code on failure.
 *         - AEE_EWOULDBLOCK: The queue is full
 *         - AEE_EBADSTATE: Bad internal state
  *        - AEE_ENOTALLOWED: write rejected - NPU scheduler enabled but remote work not started.
 */
AEEResult dspqueue_write_early_wakeup_noblock(dspqueue_t queue, uint32_t wakeup_delay, uint32_t packet_flags);


/**
 * Retrieve statistics from a queue. Statistics are relative to the queue
 * as viewed from the current endpoint (e.g. "read queue" refers to the
 * queue as being read by the current endpoint).
 *
 * Reading an accumulating statistic (such as early wakeup wait time)
 * will reset it to zero.
 *
 * Note that statistics values are only valid at the time when they're
 * read.  By the time this function returns the values may have
 * changed due to actions from another thread or the other queue
 * endpoint.
 *
 * This function is currently not supported on multi-domain queues.
 *
 * @param [in] queue Queue handle from dspqueue_create() or dspqueue_import()
 * @param [in] stat Statistic to read, see enum dspqueue_stat
 * @param [out] value Statistic value. Reading a statistic will reset it to zero
 *
 * @return 0 on success, error code on failure.
 *         - AEE_EBADPARM: Invalid statistic
 */

AEEResult dspqueue_get_stat(dspqueue_t queue, enum dspqueue_stat stat, uint64_t *value);


/** @}
 */


#ifdef __cplusplus
}
#endif

#endif //DSPQUEUE_H
