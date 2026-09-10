#pragma once
// PolyMO Lite (Xteink X4) — the single InputManager instance, shared between
// pet_pwr_x4.cpp (which owns/begins it, since pet.h ties "the physical
// buttons" to the pwr seam — see pet.h's AXP2101-era comment) and the
// FreeInkUI screen/navigation layer (not yet written — see the pet-esp32-x4
// project notes for what's still Phase 2/3).
//
// C++-only; not part of pet.h's C surface.
#include <InputManager.h>

InputManager &pet_x4_input(void);
