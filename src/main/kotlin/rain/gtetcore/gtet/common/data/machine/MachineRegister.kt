package rain.gtetcore.gtet.common.data.machine

import rain.gtetcore.gtet.common.data.machine.multiblock.ALLMmchine
import rain.gtetcore.gtet.common.data.machine.hatch.ALLSmahine

object MachineRegister {
    fun init(){
        ALLSmahine.init()
        ALLMmchine.init()
    }
}