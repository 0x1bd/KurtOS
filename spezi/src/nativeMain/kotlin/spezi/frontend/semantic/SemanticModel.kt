package spezi.frontend.semantic

import spezi.domain.ConstDef
import spezi.domain.ExternFnDef
import spezi.domain.FnDef
import spezi.domain.Program
import spezi.domain.StructDef
import spezi.domain.VarDecl

class SemanticModel(
    val program: Program,
    val structs: Map<String, StructDef>,
    val structOrder: List<StructDef>,
    val functions: List<FnDef>,
    val externals: List<ExternFnDef>,
    val constants: List<ConstDef>,
    val sharedGlobals: List<VarDecl>,
    val layout: Layout,
)
