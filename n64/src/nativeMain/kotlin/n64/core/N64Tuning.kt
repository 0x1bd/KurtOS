package n64.core

object N64Tuning {
    var cpuJit = true
    var rspJit = true
    var cpuChaining = true
    var rspChaining = true
    var skipBranch = false
    var skipLoad = false
    var skipStore = false
    var skipMul = false
    var skipShift = false
    var skipAlu = false
    var skipFpu = false
    var rdpThreads = 0

    fun reset() {
        cpuJit = true
        rspJit = true
        cpuChaining = true
        rspChaining = true
        skipBranch = false
        skipLoad = false
        skipStore = false
        skipMul = false
        skipShift = false
        skipAlu = false
        skipFpu = false
        rdpThreads = 0
    }
}
