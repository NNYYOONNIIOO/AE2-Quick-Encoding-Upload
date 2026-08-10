package com.ae2quickencodingupload.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "appeng.container.implementations.ContainerInterfaceTerminal")
public abstract class MixinContainerInterfaceTerminal {
    // The interface terminal must retain AE2's native shift-click behavior.
    // Custom matching is used only by the pattern-terminal upload action.
}
