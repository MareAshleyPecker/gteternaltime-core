package rain.gtetcore.gtet.common.data.machine

import rain.gtetcore.gtet.common.data.machine.muiltmachine.ALLMmchine
import rain.gtetcore.gtet.common.data.machine.samplemachine.ALLSmahine

object MachineRegister {
    fun init(){
        ALLSmahine.init()
        ALLMmchine.init()
    }
}