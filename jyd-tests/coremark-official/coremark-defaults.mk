# Compiler defaults for the final JYD CoreMark workload. Keep this file free
# of RTOS settings: rtthread-nano imports it only for embedded CoreMark objects.

# Zbc and Zbkx are no longer advertised after their unused operations were
# removed from the final RTL. Zbkb remains because pack is a measured hotspot.
COREMARK_DEFAULT_RISCV_ZEXTS := _zba_zbb_zbkb_zbs

COREMARK_DEFAULT_GCC_MD := 1
COREMARK_DEFAULT_XEXTS := _xmbm_xcrcu8_xdup8lo_xlistrev_xmsum_xdfa4p_xlistfind_xmacacc_xdotn_xpaddh2

COREMARK_GCC_MD_SUPPORTED_XEXTS := xbmul xmbm xcrcu8 xdup8lo xlistrev xmsum xdfa4h xdfa4p xlistfind xmacacc xdotn xpaddh2
COREMARK_MD_FLAG_xbmul := -mxbmul
COREMARK_MD_FLAG_xmbm := -mxmbm
COREMARK_MD_FLAG_xcrcu8 := -mxcrcu8
COREMARK_MD_FLAG_xdup8lo := -mxdup8lo
COREMARK_MD_FLAG_xlistrev := -mxlistrev
COREMARK_MD_FLAG_xmsum := -mclipped-rising-score-reduce
COREMARK_MD_FLAG_xdfa4h := -mxdfa4h
COREMARK_MD_FLAG_xdfa4p := -mxdfa4p
COREMARK_MD_FLAG_xlistfind := -mxlistfind
COREMARK_MD_FLAG_xmacacc := -mxmacacc
COREMARK_MD_FLAG_xdotn := -mxdotn
COREMARK_MD_FLAG_xpaddh2 := -mxpaddh2

coremark_xext_words = $(filter x%,$(subst _, ,$(strip $(1))))
coremark_md_flags = $(foreach ext,$(call coremark_xext_words,$(1)),$(COREMARK_MD_FLAG_$(ext)))
