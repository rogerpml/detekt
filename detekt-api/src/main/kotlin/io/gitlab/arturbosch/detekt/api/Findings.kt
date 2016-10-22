package io.gitlab.arturbosch.detekt.api

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.asJava.namedUnwrappedElement
import org.jetbrains.kotlin.diagnostics.DiagnosticUtils
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.getNonStrictParentOfType
import org.jetbrains.kotlin.psi.psiUtil.getTextWithLocation
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import kotlin.reflect.memberProperties

/**
 * @author Artur Bosch
 */
interface Finding : Compactable, Describable, Reflective {
	val id: String
	val location: Location
		get() = entity.location
	val entity: Entity
	val metrics: List<Metric>
	val references: List<Entity>
}

interface Reflective {
	fun findAttribute(name: String): Any? {
		return this.javaClass.kotlin.memberProperties.find { it.name == name }?.get(this)
	}
}

interface Compactable {
	fun compact(): String
}

interface Describable {
	val description: String
}

class ThresholdedCodeSmell(id: String, entity: Entity, val value: Int, val threshold: Int) : CodeSmell(id, entity) {
	override fun compact(): String {
		return "$id - $value/$threshold - l/c${location.source} - ${location.text} - ${location.file}"
	}
}

open class CodeSmell(override val id: String,
					 override val entity: Entity,
					 override val description: String = "",
					 override val metrics: List<Metric> = listOf(),
					 override val references: List<Entity> = listOf()) : Finding {
	override fun compact(): String {
		return "$id - ${entity.compact()}"
	}
}

data class Metric(val type: String,
				  val value: Int,
				  val threshold: Int,
				  val isDouble: Boolean)

data class Location(val source: SourceLocation,
					val text: TextLocation,
					val locationString: String,
					val file: String) : Compactable {

	override fun compact(): String {
		return "Line/Column=$source - CharRange=$text - $file"
	}

	companion object {

		fun from(startElement: PsiElement, endElementExclusively: PsiElement?): Location {
			if (endElementExclusively == null) return from(startElement)
			val start = startLineAndColumn(startElement)
			val sourceLocation = SourceLocation(start.line, start.column)
			val textLocation = TextLocation(startElement.startOffset, endElementExclusively.startOffset - 1)
			return Location(sourceLocation, textLocation,
					startElement.getTextWithLocation(), startElement.containingFile.name)
		}

		fun from(element: PsiElement): Location {
			val start = startLineAndColumn(element)
			val sourceLocation = SourceLocation(start.line, start.column)
			val textLocation = TextLocation(element.startOffset, element.endOffset)
			return Location(sourceLocation, textLocation,
					element.getTextWithLocation(), element.containingFile.name)
		}

		private fun startLineAndColumn(element: PsiElement) = DiagnosticUtils.getLineAndColumnInPsiFile(
				element.containingFile, element.textRange)
	}
}

data class Entity(val name: String,
				  val className: String,
				  val signature: String,
				  val location: Location) : Compactable {

	override fun compact(): String {
		return "$name/$className/$signature - ${location.compact()}"
	}

	companion object {

		fun from(startElement: PsiElement, endElementExclusively: PsiElement?): Entity {
			val name = searchName(startElement)
			val signature = searchSignature(startElement)
			val clazz = searchClass(startElement)
			return Entity(name, clazz, signature, Location.from(startElement, endElementExclusively))
		}

		fun from(element: PsiElement): Entity {
			val name = searchName(element)
			val signature = searchSignature(element)
			val clazz = searchClass(element)
			return Entity(name, clazz, signature, Location.from(element))
		}

		private fun searchSignature(element: PsiElement): String {
			return when (element) {
				is KtNamedFunction -> buildFunctionSignature(element)
				is KtClassOrObject -> buildClassSignature(element)
				else -> element.text
			}
		}

		private fun buildClassSignature(classOrObject: KtClassOrObject): String {
			var baseName = classOrObject.nameAsSafeName.asString()
			val typeParameters = classOrObject.typeParameters
			if (typeParameters.size > 0) {
				baseName += "<"
				baseName += typeParameters.joinToString(", ") { it.text }
				baseName += ">"
			}
			val extendedEntries = classOrObject.getSuperTypeListEntries()
			if (extendedEntries.size > 0) baseName += " : "
			extendedEntries.forEach { baseName += it.typeAsUserType?.referencedName ?: "" }
			return baseName
		}

		private fun buildFunctionSignature(element: KtNamedFunction): String {
			val methodStart = 0
			var methodEnd = element.endOffset - element.startOffset
			val typeReference = element.typeReference
			if (typeReference != null) {
				methodEnd = typeReference.endOffset - element.startOffset
			} else {
				element.valueParameterList?.let {
					methodEnd = it.endOffset - element.startOffset
				}
			}
			require(methodStart < methodEnd) {
				"Error building function signature with range $methodStart - $methodEnd for element: ${element.text}"
			}
			return element.text.substring(methodStart, methodEnd)
		}

		private fun searchName(element: PsiElement): String {
			return element.namedUnwrappedElement?.name ?: "<NoNameFound>"
		}

		private fun searchClass(element: PsiElement): String {
			val classElement = element.getNonStrictParentOfType(KtClassOrObject::class.java)
			var className = classElement?.name
			if (className != null && className == "Companion") {
				classElement?.parent?.getNonStrictParentOfType(KtClassOrObject::class.java)?.name?.let {
					className = it + ".$className"
				}
			}
			return className ?: element.containingFile.name
		}

	}

}

data class SourceLocation(val line: Int, val column: Int) {
	override fun toString(): String {
		return "($line,$column)"
	}
}

data class TextLocation(val start: Int, val end: Int) {
	override fun toString(): String {
		return "($start,$end)"
	}
}