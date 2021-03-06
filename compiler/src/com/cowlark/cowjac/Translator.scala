/* cowjac © 2012 David Given
 * This file is licensed under the Simplified BSD license. Please see
 * COPYING.cowjac for the full text.
 */

package com.cowlark.cowjac
import scala.collection.JavaConversions.asScalaBuffer
import scala.collection.JavaConversions.collectionAsScalaIterable
import scala.collection.immutable.HashMap
import soot.jimple.toolkits.annotation.tags.NullCheckTag
import soot.jimple.AbstractJimpleValueSwitch
import soot.jimple.AbstractStmtSwitch
import soot.jimple.AddExpr
import soot.jimple.AndExpr
import soot.jimple.ArrayRef
import soot.jimple.AssignStmt
import soot.jimple.BinopExpr
import soot.jimple.CastExpr
import soot.jimple.CaughtExceptionRef
import soot.jimple.ClassConstant
import soot.jimple.CmpExpr
import soot.jimple.CmpgExpr
import soot.jimple.CmplExpr
import soot.jimple.DefinitionStmt
import soot.jimple.DivExpr
import soot.jimple.DoubleConstant
import soot.jimple.EnterMonitorStmt
import soot.jimple.EqExpr
import soot.jimple.ExitMonitorStmt
import soot.jimple.FieldRef
import soot.jimple.FloatConstant
import soot.jimple.GeExpr
import soot.jimple.GotoStmt
import soot.jimple.GtExpr
import soot.jimple.IdentityStmt
import soot.jimple.IfStmt
import soot.jimple.InstanceFieldRef
import soot.jimple.InstanceInvokeExpr
import soot.jimple.InstanceOfExpr
import soot.jimple.IntConstant
import soot.jimple.InterfaceInvokeExpr
import soot.jimple.InvokeExpr
import soot.jimple.InvokeStmt
import soot.jimple.LeExpr
import soot.jimple.LengthExpr
import soot.jimple.LongConstant
import soot.jimple.LtExpr
import soot.jimple.MulExpr
import soot.jimple.NeExpr
import soot.jimple.NegExpr
import soot.jimple.NewArrayExpr
import soot.jimple.NewExpr
import soot.jimple.NullConstant
import soot.jimple.OrExpr
import soot.jimple.ParameterRef
import soot.jimple.RemExpr
import soot.jimple.ReturnStmt
import soot.jimple.ReturnVoidStmt
import soot.jimple.ShlExpr
import soot.jimple.ShrExpr
import soot.jimple.SpecialInvokeExpr
import soot.jimple.StaticFieldRef
import soot.jimple.StaticInvokeExpr
import soot.jimple.StringConstant
import soot.jimple.SubExpr
import soot.jimple.ThisRef
import soot.jimple.ThrowStmt
import soot.jimple.UnopExpr
import soot.jimple.UshrExpr
import soot.jimple.VirtualInvokeExpr
import soot.jimple.XorExpr
import soot.tagkit.AnnotationStringElem
import soot.tagkit.VisibilityAnnotationTag
import soot.toolkits.graph.BriefUnitGraph
import soot.ArrayType
import soot.BooleanType
import soot.ByteType
import soot.CharType
import soot.ClassMember
import soot.DoubleType
import soot.FloatType
import soot.IntType
import soot.Local
import soot.LongType
import soot.PrimType
import soot.RefLikeType
import soot.RefType
import soot.ShortType
import soot.SootClass
import soot.SootField
import soot.SootFieldRef
import soot.SootMethod
import soot.SootMethodRef
import soot.Type
import soot.TypeSwitch
import soot.VoidType
import soot.AbstractJasminClass
import soot.tagkit.Host
import soot.Scene
import soot.jimple.TableSwitchStmt
import soot.NullType
import soot.jimple.LookupSwitchStmt

object Translator extends Object with SootExtensions with Utils
{
	private var namecache = HashMap[String, String]()
	
	private val splitpoints = Array('.', '/')
	private def reformName(jname: String, separator: String): String =
		jname.split(splitpoints).reduceLeft(_ + separator + _)
		
	private val javaToCXX = Memoize((s: String) => "::" + reformName(s, "::")) 
	
	private def className(s: String) =
		javaToCXX(s)
		
	private def className(c: SootClass): String =
		className(c.getName)
		
	private def getNativeTag(m: Host): String =
	{
		for (tag <- m.getTags if tag.getName == "VisibilityAnnotationTag")
		{
			val vat = tag.asInstanceOf[VisibilityAnnotationTag]
			for (a <- vat.getAnnotations if a.getType == "Lcom/cowlark/cowjac/harmony/Native;")
			{
				val s = a.getElemAt(0).asInstanceOf[AnnotationStringElem]
				return s.getValue
			}
		}
		
		return null
	}
	
	private def getNativeMethodName(sootclass: SootClass, signature: String): String =
	{
		if (!sootclass.declaresMethod(signature))
			return null
			
		return getNativeTag(sootclass.getMethod(signature))
	}
	
	private def getNativeFieldName(sootclass: SootClass, signature: String): String =
	{
		if (!sootclass.declaresField(signature))
			return null
			
		return getNativeTag(sootclass.getField(signature))
	}
	
	private def getRecursiveNativeMethodName(sootclass: SootClass, signature: String): String =
	{
		var c = sootclass
		
		while (true)
		{
			var n = getNativeMethodName(c, signature)
			if (n != null)
				return n
				
			if (!c.hasSuperclass)
				return null
			c = c.getSuperclass
		}
		
		return null /* oddly necessary */
	}
	
	private def getRecursiveNativeFieldName(sootclass: SootClass, signature: String): String =
	{
		var c = sootclass
		
		while (true)
		{
			var n = getNativeFieldName(c, signature)
			if (n != null)
				return n
				
			if (!c.hasSuperclass)
				return null
			c = c.getSuperclass
		}
		
		return null /* oddly necessary */
	}
	
	private def methodNameImpl(m: SootMethod): String =
	{
		/* If the method has a specific native name, use that. */
		
		var nativename = getRecursiveNativeMethodName(m.getDeclaringClass,
				m.getSubSignature)
		if (nativename != null)
			return nativename
		
		/* Otherwise, mangle the name. */
			
		def hex2(i: Integer) =
			(if (i < 16) "0" else "") + Integer.toHexString(i)
			
		val sb = new StringBuilder("m_")
		val namewithsignature = m.getName + "_" +
				AbstractJasminClass.jasminDescriptorOf(m.makeRef)
		for (c <- namewithsignature)
		{
			if (c.isLetterOrDigit)
				sb += c
			else
			{
				sb += '_'
				sb ++= hex2(c.toInt)
			}
		}
		
		return sb.toString
	}
	
	private val methodName = Memoize(methodNameImpl)
	
	private def methodName(m: SootMethodRef): String =
		methodName(m.resolve)
	
	private def fieldNameImpl(m: SootField): String =
	{
		var nativename = getRecursiveNativeFieldName(m.getDeclaringClass,
				m.getSubSignature)
		if (nativename != null)
			return nativename
		
		def hex2(i: Integer) =
			(if (i < 16) "0" else "") + Integer.toHexString(i)
			
		val sb = new StringBuilder("f_")
		val name = m.getName
		for (c <- name)
		{
			if (c.isLetterOrDigit)
				sb += c
			else
			{
				sb += '_'
				sb ++= hex2(c.toInt)
			}
		}
		
		return sb.toString
	}
	
	private val fieldName = Memoize(fieldNameImpl)
	
	private def fieldName(m: SootFieldRef): String =
		fieldName(m.resolve)
	
	private def translateModifier(cm: ClassMember, p: Printer)
	{
		if (cm.isPrivate)
			p.print("private: ")
		else
			p.print("public: ")
			
		if (cm.isStatic)
			p.print("static ")			
	}
	
	private def translateType(t: Type, p: Printer)
	{
		object TS extends TypeSwitch
		{
			override def caseVoidType(t: VoidType) = p.print("void")
			override def caseBooleanType(t: BooleanType) = p.print("jboolean")
			override def caseByteType(t: ByteType) = p.print("jbyte")
			override def caseCharType(t: CharType) = p.print("jchar")
			override def caseShortType(t: ShortType) = p.print("jshort")
			override def caseIntType(t: IntType) = p.print("jint")
			override def caseLongType(t: LongType) = p.print("jlong")
			override def caseFloatType(t: FloatType) = p.print("jfloat")
			override def caseDoubleType(t: DoubleType) = p.print("jdouble")
			override def caseNullType(t: NullType) = p.print("::java::lang::Object*")
			
			override def caseArrayType(t: ArrayType)
			{
				if (t.getElementType.isInstanceOf[RefLikeType])
					p.print("::com::cowlark::cowjac::ObjectArray*")
				else
				{
					p.print("::com::cowlark::cowjac::ScalarArray< ")
					t.getElementType.apply(TS)
					p.print(" >*")
				}
			}
			
			override def caseRefType(t: RefType)
			{
				p.print(className(t.getSootClass), "*")
			}
			
			override def defaultCase(t: Type) = assert(false)
		}
		t.apply(TS)
	}
	
	private def classConstant(t: Type): String =
	{
		var result: String = null;
		
		object TS extends TypeSwitch
		{
			override def caseBooleanType(t: BooleanType) = result = "::com::cowlark::cowjac::PrimitiveBooleanClassConstant"
			override def caseByteType(t: ByteType) = result = "::com::cowlark::cowjac::PrimitiveByteClassConstant"
			override def caseCharType(t: CharType) = result = "::com::cowlark::cowjac::PrimitiveCharClassConstant"
			override def caseShortType(t: ShortType) = result = "::com::cowlark::cowjac::PrimitiveShortClassConstant"
			override def caseIntType(t: IntType) = result = "::com::cowlark::cowjac::PrimitiveIntClassConstant"
			override def caseLongType(t: LongType) = result = "::com::cowlark::cowjac::PrimitiveLongClassConstant"
			override def caseFloatType(t: FloatType) = result = "::com::cowlark::cowjac::PrimitiveFloatClassConstant"
			override def caseDoubleType(t: DoubleType) = result = "::com::cowlark::cowjac::PrimitiveDoubleClassConstant"
			
			override def caseArrayType(t: ArrayType)
			{
				t.getArrayElementType().apply(TS)
				result += "->getArrayType(&F)"
			}
			
			override def caseRefType(t: RefType) =
				result = className(t.getSootClass) + "::getClassConstant(&F)"
			
			override def defaultCase(t: Type) = assert(false)
		}
		t.apply(TS)
		return result
	}
	
	def translate(sootclass: SootClass, ps: PrintSet)
	{
		var stringconstants = Map.empty[String, Integer]
		
		def translateFieldDeclaration(field: SootField)
		{
			val isref = field.getType.isInstanceOf[RefLikeType]
	
			ps.h.print("\t")
			translateModifier(field, ps.h)
			translateType(field.getType, ps.h)
			ps.h.print(" (", fieldName(field), ");\n")
		}
		
		def translateFieldDefinition(field: SootField)
		{
			if (field.isStatic)
			{
				val isref = field.getType.isInstanceOf[RefLikeType]
				
				translateType(field.getType, ps.ch)
				ps.ch.print(" (", className(field.getDeclaringClass), "::",
						fieldName(field), ");\n")
			}
		}
		
		def translateMethodDeclaration(method: SootMethod)
		{
			var pure = isMethodPure(method)
			
			/* If we're *not* pure, and this method has no implementation,
			 * then there's no need to actually declare the method here, as
			 * it's already declared; and it will confuse C++.
			 */
				
			if (!pure && (method.isAbstract || sootclass.isInterface)) 
				return
			
			/* Ordinary method. */
			
			ps.h.print("\t")
			translateModifier(method, ps.h)
			
			if (!method.isPrivate && !method.isStatic)
				ps.h.print("virtual ")
				
			translateType(method.getReturnType, ps.h)
			ps.h.print(" ", methodName(method))
	
			ps.h.print("(com::cowlark::cowjac::Stackframe*")
			
			for (to <- method.getParameterTypes)
			{
				val t = to.asInstanceOf[Type]
	
				ps.h.print(", ")
				translateType(t, ps.h)
			}
				
			ps.h.print(")")
			
			if (pure)
				ps.h.print(" = 0")
				
			ps.h.print(";\n")
		}
		
		def translateMethodDefinition(method: SootMethod)
		{
			val body = method.getActiveBody
			
			ps.c.print("\n/* ", method.getSubSignature, " */\n")
			translateType(method.getReturnType, ps.c)
			ps.c.print(" (", className(method.getDeclaringClass), "::",
					methodName(method),
					")(com::cowlark::cowjac::Stackframe* parentFrame")
			
			for (i <- 0 until method.getParameterCount)
			{
				val t = method.getParameterType(i)
				
				ps.c.print(", ")
				translateType(t, ps.c)
				ps.c.print(" p", String.valueOf(i))
			}
			
			ps.c.print(")\n{\n")
			
			/* If this is a static method, ensure the class has been
			 * initialised. */
			
			if (method.isStatic)
				ps.c.print("\t", className(sootclass), "::classInit(parentFrame);\n")
				
			/* Declare stackframe structure. */
	
			ps.c.print("\tstruct frame : public com::cowlark::cowjac::Stackframe\n")
			ps.c.print("\t{\n");
			ps.c.print("\t\tframe(com::cowlark::cowjac::Stackframe* p):\n")
			ps.c.print("\t\t\tcom::cowlark::cowjac::Stackframe(p)\n")
		
			val reflike = body.getLocals.filter(s => s.getType.isInstanceOf[RefLikeType])
			
			ps.c.print("\t\t{\n")
			if (!reflike.isEmpty)
			{
				ps.c.print("\t\t\tmemset(&f",
						reflike.first.getName,
						", 0, sizeof(f",
						reflike.first.getName,
						") * ",
						String.valueOf(reflike.size),
						");\n")
			}
			ps.c.print("\t\t}\n")
			
			ps.c.print("\n")
			
			if (!reflike.isEmpty)
			{
				ps.c.print("\t\tvoid mark()\n")
				ps.c.print("\t\t{\n")
				
				ps.c.print("\t\t\tmarkMany(&f",
						reflike.first.getName, ", ",
						String.valueOf(reflike.size),
						");\n")
				
				ps.c.print("\t\t}\n")
			}
			
			ps.c.print("\n")
			ps.c.print("public:\n")
			
			for (local <- reflike)
			{
				val t = local.getType
	
				ps.c.print("\t\t::com::cowlark::cowjac::ContainsReferences* ",
						"f", local.getName, ";\n")
			}
	
			ps.c.print("\t};\n");
			ps.c.print("\tframe F(parentFrame);\n")
			ps.c.print("\t::com::cowlark::cowjac::Object* caughtexception;\n")
			ps.c.print("\n")
			
			/* Declare locals. */
			
			for (local <- body.getLocals)
			{
				val t = local.getType
				ps.c.print("\t/* Real type: ", t.toString, " */\n")
				ps.c.print("\t")
				translateType(Type.toMachineType(t), ps.c)
				ps.c.print(" j", local.getName, " = 0;\n")
			}
			
			/* The method body itself. */
			
			var labels = HashMap.empty[soot.Unit, Integer]
			val ug = new BriefUnitGraph(body)
			var notnull = false
			
			def label(unit: soot.Unit): String =
			{
				val s = labels.get(unit)
				if (s != None)
					return "L" + s.get
	
				val i = labels.size
				labels += (unit -> i)
				
				return "L" + i
			}
	
			object NS extends TypeSwitch
			{
				override def caseRefType(t: RefType) =
					ps.c.print(javaToCXX(t.getSootClass.getName))
				
				override def defaultCase(t: Type) = assert(false)
			}
	
			object VS extends AbstractJimpleValueSwitch
			{
				override def caseIntConstant(s: IntConstant) =
					ps.c.print("(jint)0x", s.value.toHexString)
				
				override def caseLongConstant(s: LongConstant) =
					ps.c.print("(jlong)0x", s.value.toHexString, "LL")
				
				override def caseFloatConstant(s: FloatConstant) =
				{
					val value = s.value
					if (value.isNegInfinity)
						ps.c.print("-INFINITY")
					else if (value.isPosInfinity)
						ps.c.print("INFINITY")
					else if (value.isNaN)
						ps.c.print("NAN")
					else
						ps.c.print(s.value.toString, "f")
				}
				
				override def caseDoubleConstant(s: DoubleConstant) =
				{
					val value = s.value
					if (value.isNegInfinity)
						ps.c.print("-INFINITY")
					else if (value.isPosInfinity)
						ps.c.print("INFINITY")
					else if (value.isNaN)
						ps.c.print("NAN")
					else
						ps.c.print(s.value.toString)
				}
				
				override def caseStringConstant(s: StringConstant) =
				{
					val cido = stringconstants.get(s.value)
					val cid =
						if (cido == None)
						{
							val n = stringconstants.size
							stringconstants += (s.value -> n)
							n
						}
						else
							cido.get
							
					ps.c.print("sc", String.valueOf(cid))
				}
				
				override def caseNullConstant(s: NullConstant) =
					ps.c.print("0")
					
				override def caseClassConstant(s: ClassConstant) =
				{
					/* s.value is a path-style classname, with / separators.
					 * We want one with . instead. */

					val name = s.value.replace('/', '.')
					val sc = Scene.v.getSootClass(name)
					ps.c.print(classConstant(sc.getType))
				}
					
				override def caseThisRef(v: ThisRef) =
					ps.c.print("this")
					
				override def caseLocal(v: Local) =
					ps.c.print("j", v.getName)
				
				override def caseInstanceFieldRef(v: InstanceFieldRef) =
				{
					v.getBase.apply(VS)
					ps.c.print("->", className(v.getFieldRef.declaringClass),
							"::", fieldName(v.getFieldRef))
				}
				
				override def caseStaticFieldRef(v: StaticFieldRef) =
					if (v.getFieldRef.declaringClass == sootclass)
						ps.c.print(fieldName(v.getFieldRef))
					else
						ps.c.print("(", className(v.getFieldRef.declaringClass), "::classInit(&F), ",
								className(v.getFieldRef.declaringClass), "::", fieldName(v.getFieldRef),
								")")
				
				override def caseArrayRef(v: ArrayRef) =
				{
					val t = v.getType
					var needscast = t.isInstanceOf[RefLikeType]
					
					if (needscast)
					{
						ps.c.print("dynamic_cast< ")
						translateType(v.getType, ps.c)
						ps.c.print(" >(")
					}
					
					if (!notnull)
						ps.c.print("::com::cowlark::cowjac::NullCheck(")
					v.getBase.apply(VS)
					if (!notnull)
						ps.c.print(")")
					ps.c.print("->get(&F, ")
					v.getIndex.apply(VS)
					ps.c.print(")")
					
					if (needscast)
						ps.c.print(")")
				}
				
				override def caseLengthExpr(v: LengthExpr) =
				{
					if (!notnull)
						ps.c.print("::com::cowlark::cowjac::NullCheck(")
					v.getOp.apply(VS)
					if (!notnull)
						ps.c.print(")")
					ps.c.print("->length()")
				}
					
				override def caseParameterRef(v: ParameterRef) =
					ps.c.print("p", String.valueOf(v.getIndex))
				
				override def caseCastExpr(v: CastExpr) =
				{
					if (v.getCastType.isInstanceOf[PrimType])
					{
						ps.c.print("(")
						translateType(v.getCastType, ps.c)
						ps.c.print(")(")
						v.getOp.apply(VS)
						ps.c.print(")")
					}
					else
					{
						ps.c.print("::com::cowlark::cowjac::Cast< ")
						translateType(v.getOp.getType, ps.c)
						ps.c.print(", ")
						translateType(v.getCastType, ps.c)
						ps.c.print(" >(&F, ")
						v.getOp.apply(VS)
						ps.c.print(")")
					}
				}
				
				override def caseInstanceOfExpr(v: InstanceOfExpr) =
				{
					ps.c.print("!!dynamic_cast< ")
					translateType(v.getCheckType, ps.c)
					ps.c.print(" >(")
					v.getOp.apply(VS)
					ps.c.print(")")
				}
				
				override def caseAddExpr(v: AddExpr) = caseBinopExpr(v)
				override def caseSubExpr(v: SubExpr) = caseBinopExpr(v)
				override def caseMulExpr(v: MulExpr) = caseBinopExpr(v)
				override def caseDivExpr(v: DivExpr) = caseBinopExpr(v)
				override def caseRemExpr(v: RemExpr) = caseBinopExpr(v)
				override def caseShlExpr(v: ShlExpr) = caseBinopExpr(v)
				override def caseShrExpr(v: ShrExpr) = caseBinopExpr(v)
				override def caseUshrExpr(v: UshrExpr) = caseBinopXExpr(v, "Ushr")
				override def caseGeExpr(v: GeExpr) = caseBinopExpr(v)
				override def caseGtExpr(v: GtExpr) = caseBinopExpr(v)
				override def caseLeExpr(v: LeExpr) = caseBinopExpr(v)
				override def caseLtExpr(v: LtExpr) = caseBinopExpr(v)
				override def caseEqExpr(v: EqExpr) = caseBinopExpr(v)
				override def caseNeExpr(v: NeExpr) = caseBinopExpr(v)
				override def caseCmpExpr(v: CmpExpr) = caseBinopXExpr(v, "Cmp")
				override def caseCmpgExpr(v: CmpgExpr) = caseBinopXExpr(v, "Cmpg")
				override def caseCmplExpr(v: CmplExpr) = caseBinopXExpr(v, "Cmpl")
				override def caseAndExpr(v: AndExpr) = caseBinopExpr(v)
				override def caseOrExpr(v: OrExpr) = caseBinopExpr(v)
				override def caseXorExpr(v: XorExpr) = caseBinopExpr(v)
				
				private def caseBinopExpr(v: BinopExpr) =
				{
					v.getOp1.apply(VS)
					ps.c.print(v.getSymbol)
					v.getOp2.apply(VS)
				}
	
				private def caseBinopXExpr(v: BinopExpr, x: String) =
				{
					ps.c.print("::com::cowlark::cowjac::", x, "(")
					v.getOp1.apply(VS)
					ps.c.print(", ")
					v.getOp2.apply(VS)
					ps.c.print(")")
				}
				
				override def caseNegExpr(v: NegExpr) =
				{
					ps.c.print("-")
					v.getOp.apply(VS)
				}
				
				override def caseNewExpr(v: NewExpr) =
				{
					val t = v.getType
					if (t.isInstanceOf[RefType])
					{
						val rt = t.asInstanceOf[RefType]
						ps.c.print("(", className(rt.getSootClass), "::classInit(&F), ")
						ps.c.print("new ")
						v.getType.apply(NS)
						ps.c.print(")")
					}
					else
					{
						v.getType.apply(NS)
						ps.c.print(")")
					}
				}
				
				override def caseNewArrayExpr(v: NewArrayExpr) =
				{
					val t = v.getBaseType
					
					ps.c.print("new ")
					if (t.isInstanceOf[RefLikeType])
						ps.c.print("::com::cowlark::cowjac::ObjectArray")
					else
					{
						ps.c.print("::com::cowlark::cowjac::ScalarArray< ")
						translateType(t, ps.c)
						ps.c.print(" >")
					}
					ps.c.print("(&F, ", classConstant(t), "->getArrayType(&F), ")
					v.getSize.apply(VS)
					ps.c.print(")")
				}
				
				private def parameters(v: InvokeExpr)
				{
					ps.c.print("(&F")
					
					for (arg <- v.getArgs)
					{
						ps.c.print(", ")
						arg.apply(VS)
					}
					
					ps.c.print(")")
				}
				
				override def caseInterfaceInvokeExpr(v: InterfaceInvokeExpr) =
					caseInstanceInvokeExpr(v)
					
				override def caseVirtualInvokeExpr(v: VirtualInvokeExpr) =
					caseInstanceInvokeExpr(v)
					
				def caseInstanceInvokeExpr(v: InstanceInvokeExpr) =
				{
					if (!notnull)
						ps.c.print("com::cowlark::cowjac::NullCheck(")
					v.getBase.apply(VS)
					if (!notnull)
						ps.c.print(")")
						
					ps.c.print("->", methodName(v.getMethodRef))
					parameters(v)
				}
					
				override def caseSpecialInvokeExpr(v: SpecialInvokeExpr) =
				{
					if (!notnull)
						ps.c.print("com::cowlark::cowjac::NullCheck(")
					v.getBase.apply(VS)
					if (!notnull)
						ps.c.print(")")
						
					ps.c.print("->", className(v.getMethodRef.declaringClass),
							"::", methodName(v.getMethodRef))
						
					parameters(v)
				}
				
				override def caseStaticInvokeExpr(v: StaticInvokeExpr) =
				{
					ps.c.print(className(v.getMethodRef.declaringClass), "::",
							methodName(v.getMethodRef))
					
					parameters(v)
				}
				
				override def defaultCase(s: Any) = assert(false)
			}
			
			object SS extends AbstractStmtSwitch
			{
				override def caseIdentityStmt(s: IdentityStmt) = caseDefinitionStmt(s)
				override def caseAssignStmt(s: AssignStmt) = caseDefinitionStmt(s)
				
				override def caseReturnStmt(s: ReturnStmt) =
				{
					ps.c.print("\treturn ")
					s.getOp.apply(VS)
					ps.c.print(";\n")
				}
				
				override def caseReturnVoidStmt(s: ReturnVoidStmt) =
					ps.c.print("\treturn;\n")
				
				override def caseIfStmt(s: IfStmt) =
				{
					ps.c.print("\tif (")
					s.getCondition.apply(VS)
					ps.c.print(") goto ", label(s.getTarget), ";\n")
				}
					
				override def caseInvokeStmt(s: InvokeStmt) =
				{
					ps.c.print("\t")
					s.getInvokeExpr.apply(VS)
					ps.c.print(";\n")
				}
				
				private def assignment_source(s: DefinitionStmt)
				{
					if (s.getRightOp.isInstanceOf[CaughtExceptionRef])
					{
						ps.c.print("dynamic_cast< ")
						translateType(s.getLeftOp.getType, ps.c)
						ps.c.print(" >(caughtexception)")
					}
					else
						s.getRightOp.apply(VS)
				}
				
				def caseDefinitionStmt(s: DefinitionStmt) =
				{
					ps.c.print("\t")
					
					if (s.getLeftOp.isInstanceOf[ArrayRef])
					{
						val target = s.getLeftOp.asInstanceOf[ArrayRef];
						
						if (!notnull)
							ps.c.print("::com::cowlark::cowjac::NullCheck(")
						target.getBase.apply(VS)
						if (!notnull)
							ps.c.print(")")
						ps.c.print("->set(&F, ")
						target.getIndex.apply(VS)
						ps.c.print(", ")
						assignment_source(s)
						ps.c.print(")")
					}
					else
					{
						if (s.getLeftOp.isInstanceOf[Local] &&
								s.getLeftOp.getType.isInstanceOf[RefLikeType])
						{
							/* Assign to local with is a reference; must remember
							 * to update the stack frame to make GC work. */
							val local = s.getLeftOp.asInstanceOf[Local]
							ps.c.print("F.f", local.getName, " = ")
						}
						s.getLeftOp.apply(VS)
						ps.c.print(" = ")
						assignment_source(s)
					}
					
					ps.c.print(";\n")
				}
				
				override def caseThrowStmt(s: ThrowStmt) =
				{
					ps.c.print("\tthrow ")
					s.getOp.apply(VS)
					ps.c.print(";\n")
				}
				
				override def caseGotoStmt(s: GotoStmt) =
					ps.c.print("\tgoto ", label(s.getTarget), ";\n")
				
				override def caseEnterMonitorStmt(s: EnterMonitorStmt) =
				{
					ps.c.print("\t")
						
					if (!notnull)
						ps.c.print("com::cowlark::cowjac::NullCheck(")
					s.getOp.apply(VS)
					if (!notnull)
						ps.c.print(")")
						
					ps.c.print("->enterMonitor();\n")
				}
				
				override def caseExitMonitorStmt(s: ExitMonitorStmt) =
				{
					ps.c.print("\t")
						
					if (!notnull)
						ps.c.print("com::cowlark::cowjac::NullCheck(")
					s.getOp.apply(VS)
					if (!notnull)
						ps.c.print(")")
						
					ps.c.print("->leaveMonitor();\n")
				}
				
				override def caseTableSwitchStmt(s: TableSwitchStmt) =
				{
					ps.c.print("\tswitch (")
					s.getKey.apply(VS)
					ps.c.print(")\n")
					ps.c.print("\t{\n")
					
					var value = s.getLowIndex
					for (to <- s.getTargets)
					{
						ps.c.print("\t\tcase ",
								value.toString, ": goto ",
								label(to.asInstanceOf[soot.Unit]),
								";\n")
						value += 1
					}
					
					ps.c.print("\t\tdefault: goto ",
							label(s.getDefaultTarget), ";\n")
					ps.c.print("\t}\n")
				}
				
				override def caseLookupSwitchStmt(s: LookupSwitchStmt) =
				{
					ps.c.print("\tswitch (")
					s.getKey.apply(VS)
					ps.c.print(")\n")
					ps.c.print("\t{\n")
					
					for (i <- 0 until s.getTargetCount)
					{
						ps.c.print("\t\tcase ",
								s.getLookupValue(i).toString, ": goto ",
								label(s.getTarget(i)),
								";\n")
					}
					
					ps.c.print("\t\tdefault: goto ",
							label(s.getDefaultTarget), ";\n")
					ps.c.print("\t}\n")
				}
				
				override def defaultCase(s: Any) = assert(false)
			}
			
			var oldunit: soot.Unit = null
			for (unit <- body.getUnits)
			{
				/* If this is a target of a jump, then we need to add a label.
				 * An instruction is not a jump target if the only way to it is
				 * from the preceding instruction. */
				
				val junction = 
					if ((ug.getPredsOf(unit).size == 1) && (ug.getPredsOf(unit).get(0) == oldunit))
						if (oldunit.isInstanceOf[TableSwitchStmt] || oldunit.isInstanceOf[LookupSwitchStmt])
							true
						else
							false
					else
						true
					
				if (junction)
					ps.c.print(label(unit), ":\n")
	
				val tag = unit.getTag("NullCheckTag").asInstanceOf[NullCheckTag]
				notnull = (tag != null) && !tag.needCheck()
				unit.apply(SS)
						
				oldunit = unit
			}
			
			ps.c.print("}\n\n")
		}
		
		def forwardDeclare(sootclass: SootClass)
		{
			val nslevels = sootclass.getName.split('.')
			for (i <- 0 to nslevels.length-2)
				ps.h.print("namespace ", nslevels(i), " { ")
			
			ps.h.print("class ", sootclass.getJavaStyleName, "; ")
			
			for (i <- 0 to nslevels.length-2)
				ps.h.print("}")
			ps.h.print("\n")
		}
		
		def emitTrampoline(frommethod: SootMethod, tomethod: SootMethod)
		{
			ps.h.print("\n\t/* (declared in ", className(frommethod.getDeclaringClass), ") */\n")
			
			ps.h.print("\t")
			translateModifier(frommethod, ps.h)
			ps.h.print("virtual ")
				
			translateType(frommethod.getReturnType, ps.h)
			ps.h.print(" ", methodName(frommethod))
	
			ps.h.print("(com::cowlark::cowjac::Stackframe* F")
			
			for (i <- 0 until frommethod.getParameterCount)
			{
				val t = frommethod.getParameterType(i)
				
				ps.h.print(", ")
				translateType(t, ps.h)
				ps.h.print(" p", String.valueOf(i))
			}
							
			ps.h.print(")\n", "\t{\n", "\t\t")

			if (!frommethod.getReturnType.isInstanceOf[VoidType])
				ps.h.print("return ")
				
			ps.h.print(className(tomethod.getDeclaringClass),
					"::", methodName(tomethod), "(F")
					
			for (i <- 0 until frommethod.getParameterCount)
			{
				val t = frommethod.getParameterType(i)
				ps.h.print(", p", String.valueOf(i))
			}
			
			ps.h.print(");\n", "\t}\n")
		}

		val jname = sootclass.getName()
		val cxxname = javaToCXX(jname)
		val headername = reformName(jname, "_").toUpperCase() + "_H"
		
		ps.h.print("#ifndef ", headername, "\n")
		ps.h.print("#define ", headername, "\n")
		
		ps.ch.print("#include \"cowjac.h\"\n")
		ps.ch.print("#include \"cowjacarray.h\"\n")
		ps.ch.print("#include \"cowjacclass.h\"\n")
		
		ps.h.print("\n")
		val dependencies = getClassDependencies(sootclass)
		for (d <- dependencies)
		{
			forwardDeclare(d)
			ps.ch.print("#include \"", mangleFilename(d.getName), ".h\"\n")
		}
		
		ps.h.print("\n")
		ps.ch.print("\n")

		ps.h.print("#include \"java.lang.Object.h\"\n")
		if (sootclass.hasSuperclass)
			ps.h.print("#include \"", mangleFilename(sootclass.getSuperclass.getName), ".h\"\n")
		for (s <- sootclass.getInterfaces)
			ps.h.print("#include \"", mangleFilename(s.getName), ".h\"\n")
		
		val nslevels = jname.split('.')
		for (i <- 0 to nslevels.length-2)
			ps.h.print("namespace ", nslevels(i), " {\n")
		
		ps.h.print("\n")

		/* Class declaration and superclasses. */
		
		ps.h.print("class ", sootclass.getJavaStyleName, " : ")
		if (sootclass.hasSuperclass)
		{
			val superclass = sootclass.getSuperclass
			if ((superclass.getName == "java.lang.Object") || superclass.isInterface)
				ps.h.print("virtual ")
			ps.h.print("public ", className(superclass))
		}
		else
			ps.h.print("public com::cowlark::cowjac::Object")

		val parentinterfaces =
			if (sootclass.hasSuperclass)
				getAllInterfaces(sootclass.getSuperclass)
			else
				Set.empty[SootClass]
		val newinterfaces = sootclass.getInterfaces.filterNot(
				parentinterfaces.contains(_))

		for (i <- newinterfaces)
			ps.h.print(", virtual public ", className(i))
		
		ps.h.print("\n{\n")
		
		ps.h.print("\t/* Class management */\n")
		ps.ch.print("/* Class management */\n")
		
		ps.h.print("\tpublic: static ::java::lang::Class* getClassConstant(::com::cowlark::cowjac::Stackframe*);\n")
		ps.h.print("\tpublic: static void classInit(::com::cowlark::cowjac::Stackframe*);\n")
		if (!sootclass.declaresMethod("java.lang.Class getClass()"))
			ps.h.print("\tpublic: virtual ::java::lang::Class* getClass(::com::cowlark::cowjac::Stackframe* F) { return getClassConstant(F); }\n")
		ps.h.print("\n")
		
		ps.h.print("\t/* Field declarations */\n")
		ps.ch.print("\n/* Field definitions */\n")
		for (f <- sootclass.getFields)
		{
			translateFieldDeclaration(f)
			translateFieldDefinition(f)
		}
		
		if (!sootclass.isInterface)
		{
			ps.h.print("\n\t/* Imported methods from superclasses */\n")
			
			var newinterfacemethods = Set.empty[SootMethod]
			for (i <- newinterfaces)
				newinterfacemethods ++= getAllInterfaceMethods(i)
			for (m <- newinterfacemethods)
			{
				val signature = m.getSubSignature
				if (!sootclass.declaresMethod(signature))
				{
					val pm = getMethodRecursively(sootclass.getSuperclass, signature)
					if (!getAllInterfaces(pm.getDeclaringClass).contains(m.getDeclaringClass))
					{
						if (!pm.isAbstract)
							emitTrampoline(m, pm)
					}
				}
			}
			
			ps.h.print("\n")
		}

		ps.h.print("\t/* Method declarations */\n")
		ps.c.print("\n/* Method definitions */\n")
		
		/* Emit destructor (required to make vtable be emitted...) */
		
		ps.h.print("\tpublic: virtual ~", sootclass.getShortName, "() {};\n")
				
		/* Ordinary methods */
				
		for (m <- sootclass.getMethods)
		{
			translateMethodDeclaration(m)
			if (m.hasActiveBody)
				translateMethodDefinition(m)
		}
		
		/* GC marking of member variables. */
		
		ps.h.print("\n\tprotected: void markImpl();\n")
		
		ps.c.print("void ", className(sootclass), "::markImpl()\n")
		ps.c.print("{\n")
		
		if (sootclass.hasSuperclass)
			ps.c.print("\t", className(sootclass.getSuperclass), "::markImpl();\n")
		for (f <- sootclass.getFields)
		{
			if (!f.isStatic && f.getType.isInstanceOf[RefLikeType])
			{
				ps.c.print("\tif (", fieldName(f), ") ",
						fieldName(f), "->mark();\n")
			}
		}

		ps.c.print("}\n")
		
		/* GC marking of class variables. */
		
		ps.h.print("\n")
		ps.h.print("\tpublic: class Marker : public ::com::cowlark::cowjac::ContainsGlobalReferences\n")
		ps.h.print("\t{\n")
		ps.h.print("\t\tpublic: void mark();\n")
		ps.h.print("\t};\n")
		ps.h.print("\n")
		
		ps.ch.print("\n/* Class marker */\n")
		ps.ch.print("\n")
		ps.ch.print("static ", className(sootclass), "::Marker* marker = 0;\n")
		ps.ch.print("void ", className(sootclass), "::Marker::mark()\n")
		ps.ch.print("{\n")
		for (f <- sootclass.getFields)
		{
			if (f.isStatic && f.getType.isInstanceOf[RefLikeType])
			{
				ps.ch.print("\tif (",
						className(sootclass), "::", fieldName(f), ") ",
						className(sootclass), "::", fieldName(f), "->mark();\n")
			}
		}
		ps.ch.print("}\n")

		/* Class initialisation. */
		
		ps.c.print("\n::java::lang::Class* ", className(sootclass),
				"::getClassConstant(::com::cowlark::cowjac::Stackframe* F)\n")
		ps.c.print("{\n")
		ps.c.print("\tstatic ::java::lang::Class* classConstant = 0;\n")
		ps.c.print("\tclassInit(F);\n")
		ps.c.print("\tif (!classConstant)\n")
		ps.c.print("\t{\n")
		ps.c.print("\t\t::com::cowlark::cowjac::SystemLock lock;\n")
		ps.c.print("\t\tif (!classConstant)\n")
		ps.c.print("\t\t\tclassConstant = new ::com::cowlark::cowjac::SimpleClass(F, \"" +
				sootclass.getName, "\");\n")
		ps.c.print("\t}\n")
		ps.c.print("\treturn classConstant;\n")
		ps.c.print("}\n")
		
		ps.c.print("\nvoid ", className(sootclass),
				"::classInit(::com::cowlark::cowjac::Stackframe* F)\n")
		ps.c.print("{\n")
		ps.c.print("\tstatic bool initialised = false;\n")
		ps.c.print("\tif (!initialised)\n")
		ps.c.print("\t{\n")
		ps.c.print("\t\t::com::cowlark::cowjac::SystemLock lock;\n")
		ps.c.print("\t\tif (!initialised)\n")
		ps.c.print("\t\t{\n")
		ps.c.print("\t\t\tinitialised = true;\n")
		
		for (sc <- stringconstants)
		{
			ps.ch.print("\nstatic const jchar scd", sc._2.toString, "[] = {")
			if (!sc._1.isEmpty)
				ps.ch.print(sc._1.map(_.toInt.toString).reduceLeft(_ + ", " + _))
			ps.ch.print("};\n")
			ps.ch.print("static ::java::lang::String* sc", sc._2.toString, " = 0;\n")
			ps.ch.print("\n")
			
			ps.c.print("\t\t\t::com::cowlark::cowjac::ScalarArray<jchar>* scda",
					sc._2.toString, " = new ::com::cowlark::cowjac::ScalarArray<jchar>(",
					"F, ::com::cowlark::cowjac::PrimitiveCharClassConstant->getArrayType(F), ",
					sc._1.length.toString, ", (jchar*) scd", sc._2.toString, ");\n")
			ps.c.print("\t\t\tsc", sc._2.toString, " = new ::java::lang::String;\n")
			ps.c.print("\t\t\tsc", sc._2.toString, "->makeImmutable();\n")
			/* This initialises the string with an internal constructor, to avoid
			 * copying the array (which causes nasty recursion issues during
			 * startup). */
			ps.c.print("\t\t\tsc", sc._2.toString, "->m__3cinit_3e_5f_28II_5bC_29V(F, ",
					"0, ", sc._1.length.toString, ", scda", sc._2.toString, ");\n")
		}
		
		ps.c.print("\t\t\tmarker = new ", className(sootclass), "::Marker();\n")
		if (sootclass.hasSuperclass)
			ps.c.print("\t\t\t", className(sootclass.getSuperclass), "::classInit(F);\n")
		for (i <- newinterfaces)
			ps.c.print("\t\t\t", className(i), "::classInit(F);\n")
		if (sootclass.declaresMethod("void <clinit>()"))
		{
			val m = sootclass.getMethod("void <clinit>()")
			ps.c.print("\t\t\t", className(sootclass), "::", methodName(m), "(F);\n")
		}
		
		ps.c.print("\t\t}\n")
		ps.c.print("\t}\n")
		ps.c.print("}\n")
		
		/* Header footer. */
		
		ps.h.print("};\n")
		ps.h.print("\n")
		
		for (i <- 0 until nslevels.length-1)
			ps.h.print("} /* namespace ", nslevels(nslevels.length-1-i), " */\n")
		
		ps.h.print("#endif\n")
	}
}