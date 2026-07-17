package kernel.drivers.gpu.vega

object VegaReg {
    const val GRBM_STATUS: UInt = 0x2004u
    const val GRBM_STATUS2: UInt = 0x2002u
    const val GRBM_GFX_CNTL: UInt = 0x2022u
    const val CP_STAT: UInt = 0x21A0u
    const val GB_ADDR_CONFIG: UInt = 0x263Eu
    const val SCRATCH_REG0: UInt = 0xC040u

    const val RLC_CNTL: UInt = 0xEC00u
    const val RLC_GPM_STAT: UInt = 0xEC40u
    const val RLC_GPM_UCODE_ADDR: UInt = 0xF83Cu
    const val RLC_GPM_UCODE_DATA: UInt = 0xF83Du

    const val CP_MEC_CNTL: UInt = 0x208Du
    const val CP_MEC_ME1_UCODE_ADDR: UInt = 0xF81Au
    const val CP_MEC_ME1_UCODE_DATA: UInt = 0xF81Bu
    const val CP_CPC_IC_BASE_LO: UInt = 0x30B9u
    const val CP_CPC_IC_BASE_HI: UInt = 0x30BAu
    const val CP_CPC_IC_BASE_CNTL: UInt = 0x30BBu

    const val CP_MQD_BASE_ADDR: UInt = 0x3245u
    const val CP_MQD_BASE_ADDR_HI: UInt = 0x3246u
    const val CP_HQD_ACTIVE: UInt = 0x3247u
    const val CP_HQD_VMID: UInt = 0x3248u
    const val CP_HQD_PERSISTENT_STATE: UInt = 0x3249u
    const val CP_HQD_PQ_BASE: UInt = 0x324Du
    const val CP_HQD_PQ_BASE_HI: UInt = 0x324Eu
    const val CP_HQD_PQ_RPTR: UInt = 0x324Fu
    const val CP_HQD_PQ_RPTR_REPORT_ADDR: UInt = 0x3250u
    const val CP_HQD_PQ_RPTR_REPORT_ADDR_HI: UInt = 0x3251u
    const val CP_HQD_PQ_DOORBELL_CONTROL: UInt = 0x3254u
    const val CP_HQD_PQ_CONTROL: UInt = 0x3256u
    const val CP_HQD_IB_CONTROL: UInt = 0x325Au
    const val CP_HQD_DEQUEUE_REQUEST: UInt = 0x325Du
    const val CP_MQD_CONTROL: UInt = 0x3267u
    const val CP_HQD_PQ_WPTR_LO: UInt = 0x327Bu
    const val CP_HQD_PQ_WPTR_HI: UInt = 0x327Cu

    const val COMPUTE_NUM_THREAD_X: UInt = 0x2E07u
    const val COMPUTE_NUM_THREAD_Y: UInt = 0x2E08u
    const val COMPUTE_NUM_THREAD_Z: UInt = 0x2E09u
    const val COMPUTE_PGM_LO: UInt = 0x2E0Cu
    const val COMPUTE_PGM_HI: UInt = 0x2E0Du
    const val COMPUTE_PGM_RSRC1: UInt = 0x2E12u
    const val COMPUTE_PGM_RSRC2: UInt = 0x2E13u
    const val COMPUTE_RESOURCE_LIMITS: UInt = 0x2E15u
    const val COMPUTE_STATIC_THREAD_MGMT_SE0: UInt = 0x2E16u
    const val COMPUTE_STATIC_THREAD_MGMT_SE1: UInt = 0x2E17u
    const val COMPUTE_TMPRING_SIZE: UInt = 0x2E18u
    const val COMPUTE_STATIC_THREAD_MGMT_SE2: UInt = 0x2E19u
    const val COMPUTE_STATIC_THREAD_MGMT_SE3: UInt = 0x2E1Au
    const val COMPUTE_USER_DATA_0: UInt = 0x2E40u

    const val SDMA0_UCODE_ADDR: UInt = 0x1260u
    const val SDMA0_UCODE_DATA: UInt = 0x1261u
    const val SDMA0_CNTL: UInt = 0x127Cu
    const val SDMA0_STATUS_REG: UInt = 0x1285u
    const val SDMA0_F32_CNTL: UInt = 0x128Au
    const val SDMA0_GFX_RB_CNTL: UInt = 0x12E0u
    const val SDMA0_GFX_RB_BASE: UInt = 0x12E1u
    const val SDMA0_GFX_RB_BASE_HI: UInt = 0x12E2u
    const val SDMA0_GFX_RB_RPTR: UInt = 0x12E3u
    const val SDMA0_GFX_RB_RPTR_HI: UInt = 0x12E4u
    const val SDMA0_GFX_RB_WPTR: UInt = 0x12E5u
    const val SDMA0_GFX_RB_WPTR_HI: UInt = 0x12E6u
    const val SDMA0_GFX_RB_RPTR_ADDR_HI: UInt = 0x12E8u
    const val SDMA0_GFX_RB_RPTR_ADDR_LO: UInt = 0x12E9u
    const val SDMA0_GFX_IB_CNTL: UInt = 0x12EAu
    const val SDMA0_GFX_DOORBELL: UInt = 0x12F2u
    const val SDMA0_GFX_DOORBELL_OFFSET: UInt = 0x130Bu
    const val SDMA0_GFX_RB_WPTR_POLL_CNTL: UInt = 0x12E7u
    const val SDMA0_GFX_RB_WPTR_POLL_ADDR_HI: UInt = 0x1312u
    const val SDMA0_GFX_RB_WPTR_POLL_ADDR_LO: UInt = 0x1313u
    const val SDMA0_GFX_MINOR_PTR_UPDATE: UInt = 0x1315u
    const val SDMA0_SEM_WAIT_FAIL_TIMER_CNTL: UInt = 0x1281u
    const val SDMA0_UTCL1_TIMEOUT: UInt = 0x12A7u
    const val SDMA_AUTO_CTXSW: UInt = 0x40000u

    const val HDP_READ_CACHE_INVALIDATE: UInt = 0xFF1u

    const val RCC_DOORBELL_APER_EN: UInt = 0xDE0u
    const val BIF_SDMA0_DOORBELL_RANGE: UInt = 0xEF0u
    const val SDMA0_DOORBELL_INDEX: UInt = 0xF0u

    const val REMAP_HDP_MEM_FLUSH_CNTL: UInt = 0xE4Du
    const val HDP_FLUSH_BAR_OFFSET: UInt = 0x7F000u
    const val HDP_FLUSH_REG: UInt = 0x1FC00u

    const val MC_VM_FB_OFFSET: UInt = 0x1A817u
    const val MC_VM_FB_LOCATION_BASE: UInt = 0x1A82Cu
    const val MC_VM_FB_LOCATION_TOP: UInt = 0x1A82Du
    const val MC_VM_AGP_TOP: UInt = 0x1A82Eu
    const val MC_VM_AGP_BOT: UInt = 0x1A82Fu
    const val MC_VM_AGP_BASE: UInt = 0x1A830u
    const val MC_VM_SYSTEM_APERTURE_LOW_ADDR: UInt = 0x1A831u
    const val MC_VM_SYSTEM_APERTURE_HIGH_ADDR: UInt = 0x1A832u
    const val MC_VM_SYSTEM_APERTURE_DEFAULT_ADDR_LSB: UInt = 0x1A818u
    const val MC_VM_SYSTEM_APERTURE_DEFAULT_ADDR_MSB: UInt = 0x1A819u

    const val MC_VM_MX_L1_TLB_CNTL: UInt = 0x1A833u
    const val VM_L2_CNTL: UInt = 0x1A680u
    const val VM_L2_CNTL2: UInt = 0x1A681u
    const val VM_L2_CNTL3: UInt = 0x1A682u
    const val VM_L2_CNTL4: UInt = 0x1A697u
    const val VM_L2_PROTECTION_FAULT_CNTL: UInt = 0x1A687u
    const val VM_L2_PROTECTION_FAULT_STATUS: UInt = 0x1A68Bu
    const val VM_L2_PROTECTION_FAULT_DEFAULT_ADDR_LO32: UInt = 0x1A68Eu
    const val VM_L2_PROTECTION_FAULT_DEFAULT_ADDR_HI32: UInt = 0x1A68Fu
    const val VM_CONTEXT0_CNTL: UInt = 0x1A6C0u
    const val VM_CONTEXT0_PAGE_TABLE_BASE_ADDR_LO32: UInt = 0x1A72Bu
    const val VM_CONTEXT0_PAGE_TABLE_BASE_ADDR_HI32: UInt = 0x1A72Cu
    const val VM_CONTEXT0_PAGE_TABLE_START_ADDR_LO32: UInt = 0x1A74Bu
    const val VM_CONTEXT0_PAGE_TABLE_START_ADDR_HI32: UInt = 0x1A74Cu
    const val VM_CONTEXT0_PAGE_TABLE_END_ADDR_LO32: UInt = 0x1A76Bu
    const val VM_CONTEXT0_PAGE_TABLE_END_ADDR_HI32: UInt = 0x1A76Cu

    const val L1_TLB_CNTL_VALUE: UInt = 0x3859u
    const val L2_CNTL_ENABLE: UInt = 0x80003u
    const val L2_CNTL2_VALUE: UInt = 0x3u
    const val L2_CNTL3_VALUE: UInt = 0x80100007u
    const val L2_CNTL4_VALUE: UInt = 0x1u

    const val MP1_C2PMSG_66: UInt = 0x16282u
    const val MP1_C2PMSG_82: UInt = 0x16292u
    const val MP1_C2PMSG_90: UInt = 0x1629Au

    const val MP0_C2PMSG_33: UInt = 0x16061u
    const val MP0_C2PMSG_35: UInt = 0x16063u
    const val MP0_C2PMSG_64: UInt = 0x16080u
    const val MP0_C2PMSG_67: UInt = 0x16083u
    const val MP0_C2PMSG_69: UInt = 0x16085u
    const val MP0_C2PMSG_70: UInt = 0x16086u
    const val MP0_C2PMSG_71: UInt = 0x16087u
    const val MP0_C2PMSG_81: UInt = 0x16091u

    const val PSP_RING_TYPE_KM: UInt = 2u
    const val GFX_FW_TYPE_CP_ME: UInt = 1u
    const val GFX_FW_TYPE_CP_PFP: UInt = 2u
    const val GFX_FW_TYPE_CP_CE: UInt = 3u
    const val GFX_FW_TYPE_CP_MEC: UInt = 4u
    const val GFX_FW_TYPE_CP_MEC_ME1: UInt = 5u
    const val GFX_FW_TYPE_RLC_G: UInt = 8u
    const val GFX_FW_TYPE_SDMA0: UInt = 9u
    const val GFX_CMD_ID_SETUP_TMR: UInt = 5u
    const val GFX_CMD_ID_LOAD_IP_FW: UInt = 6u

    const val SMU_MSG_TEST: UInt = 0x1u
    const val SMU_MSG_GET_SMU_VERSION: UInt = 0x2u
    const val SMU_MSG_POWER_UP_GFX: UInt = 0x6u
    const val SMU_MSG_ENABLE_GFXOFF: UInt = 0x7u
    const val SMU_MSG_DISABLE_GFXOFF: UInt = 0x8u
    const val SMU_MSG_POWER_UP_SDMA: UInt = 0xEu
    const val SMU_RESULT_OK: UInt = 0x1u

    const val SDMA_F32_HALT: UInt = 0x1u
    const val SDMA_RB_ENABLE: UInt = 0x1u
    const val SDMA_RB_SIZE_SHIFT: Int = 1
    const val SDMA_RB_SIZE_MASK: UInt = 0x7Eu
    const val SDMA_RPTR_WRITEBACK_ENABLE: UInt = 0x1000u
    const val SDMA_DOORBELL_ENABLE: UInt = 0x10000000u
    const val SDMA_UTC_L1_ENABLE: UInt = 0x2u
    const val SDMA_IB_ENABLE: UInt = 0x1u
    const val SDMA_F32_POLL_ENABLE: UInt = 0x4u
    const val SDMA_WPTR_POLL_ENABLE: UInt = 0x1u

    const val SDMA_OP_NOP: UInt = 0u
    const val SDMA_OP_COPY: UInt = 1u
    const val SDMA_OP_WRITE: UInt = 2u
    const val SDMA_OP_FENCE: UInt = 5u
    const val SDMA_OP_TRAP: UInt = 6u
    const val SDMA_SUBOP_COPY_LINEAR: UInt = 0u

    const val MEC_ME1_HALT: UInt = 0x40000000u
    const val MEC_ME2_HALT: UInt = 0x10000000u

    const val REGISTER_APERTURE_BYTES: ULong = 0x80000UL
}
