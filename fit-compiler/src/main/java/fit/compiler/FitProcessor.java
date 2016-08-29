package fit.compiler;

import com.google.auto.common.SuperficialValidation;
import com.google.auto.service.AutoService;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import fit.SharedPreferenceAble;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;

import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PUBLIC;

@AutoService(Processor.class) public class FitProcessor extends AbstractProcessor {
  private static final ClassName MM = ClassName.get("fit", "MM");
  private static final ClassName CONTEXT = ClassName.get("android.content", "Context");
  private static final ClassName SHARED_PREFERENCES =
      ClassName.get("android.content", "SharedPreferences");

  private static final String METHOD_GET_STRING = "getString";
  private static final String METHOD_GET_Int = "getInt";

  private Elements elementUtils;
  private Filer filer;

  @Override public synchronized void init(ProcessingEnvironment env) {
    super.init(env);

    elementUtils = env.getElementUtils();
    //typeUtils = env.getTypeUtils();
    filer = env.getFiler();
    //try {
    //  trees = Trees.instance(processingEnv);
    //} catch (IllegalArgumentException ignored) {
    //}
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {

    // Process each @SharedPreferenceAble element.
    for (Element element : roundEnv.getElementsAnnotatedWith(SharedPreferenceAble.class)) {
      if (!SuperficialValidation.validateElement(element)) continue;
      // we don't SuperficialValidation.validateElement(element)
      // so that an unresolved View type can be generated by later processing rounds
      try {
        TypeElement enclosingElement = (TypeElement) element;

        // Assemble information on the field.

        TypeName targetType = TypeName.get(enclosingElement.asType());
        if (targetType instanceof ParameterizedTypeName) {
          targetType = ((ParameterizedTypeName) targetType).rawType;
        }

        String packageName = getPackageName(enclosingElement);
        String className = getClassName(enclosingElement, packageName);
        ClassName preferenceClassName = ClassName.get(packageName, className + "_Preference");

        boolean isFinal = enclosingElement.getModifiers().contains(Modifier.FINAL);

        List<Element> fieldElements = new ArrayList<>();
        for (Element memberElement : elementUtils.getAllMembers(enclosingElement)) {
          if (memberElement.getKind() == ElementKind.FIELD) {
            fieldElements.add(memberElement);
          }
        }

        JavaFile javaFile = JavaFile.builder(preferenceClassName.packageName(),
            createPreferenceClass(preferenceClassName, isFinal, targetType, fieldElements))
            .addFileComment("Generated code from Fit. Do not modify!")
            .build();
        javaFile.writeTo(filer);
      } catch (Exception e) {
        logParsingError(element, SharedPreferenceAble.class, e);
      }
    }
    return false;
  }

  private String getPackageName(TypeElement type) {
    return elementUtils.getPackageOf(type).getQualifiedName().toString();
  }

  private static String getClassName(TypeElement type, String packageName) {
    int packageLen = packageName.length() + 1;
    return type.getQualifiedName().toString().substring(packageLen).replace('.', '$');
  }

  private TypeSpec createPreferenceClass(ClassName preferenceClassName, boolean isFinal,
      TypeName targetTypeName, List<Element> fieldElements) {
    TypeSpec.Builder result =
        TypeSpec.classBuilder(preferenceClassName.simpleName()).addModifiers(PUBLIC);

    if (isFinal) {
      result.addModifiers(FINAL);
    }

    ParameterizedTypeName parameterizedTypeName = ParameterizedTypeName.get(MM, targetTypeName);
    result.addSuperinterface(parameterizedTypeName);
    result.addMethod(createPreferenceSaveMethod(result, targetTypeName, fieldElements));
    result.addMethod(createPreferenceGetMethod(result, targetTypeName, fieldElements));
    return result.build();
  }

  private MethodSpec createPreferenceSaveMethod(TypeSpec.Builder preferenceClass,
      TypeName targetType, List<Element> fieldElements) {
    MethodSpec.Builder result = MethodSpec.methodBuilder("save")
        .addAnnotation(Override.class)
        .addModifiers(PUBLIC)
        .addParameter(CONTEXT, "context")
        .addParameter(targetType, "obj")
        .addStatement(
            "$T sharedPreferences = context.getSharedPreferences($S, Context.MODE_PRIVATE)",
            SHARED_PREFERENCES, targetType.toString())
        .addStatement("SharedPreferences.Editor editor = sharedPreferences.edit()");

    for (Element element : fieldElements) {
      TypeName fieldTypeName = TypeName.get(element.asType());
      String putMethod = "";
      String valueL = "obj.$L";
      System.out.println("filed:" + fieldTypeName + "  " + (TypeName.INT.equals(fieldTypeName)
          || TypeName.BYTE.equals(fieldTypeName)
          || TypeName.SHORT.equals(fieldTypeName)
          || TypeName.CHAR.equals(fieldTypeName)));
      // FIXME: 8/29/16 type
      if (TypeName.get(String.class).equals(fieldTypeName)) {
        putMethod = "putString";
      } else if (TypeName.BOOLEAN.equals(fieldTypeName)) {
        putMethod = "putBoolean";
      } else if (TypeName.FLOAT.equals(fieldTypeName)) {
        putMethod = "putFloat";
      } else if (TypeName.INT.equals(fieldTypeName)
          || TypeName.BYTE.equals(fieldTypeName)
          || TypeName.SHORT.equals(fieldTypeName)
          || TypeName.CHAR.equals(fieldTypeName)) {
        putMethod = "putInt";
      } else if (TypeName.LONG.equals(fieldTypeName)) {
        putMethod = "putLong";
      } else if (TypeName.DOUBLE.equals(fieldTypeName)) {
        putMethod = "putLong";
        valueL = "Double.doubleToLongBits(" + valueL + ")";
      } else {
        continue;
      }
      //else if (TypeName.get(Set.class).equals(fieldTypeName)) {
      //  putMethod = "putStringSet";
      //}
      result.addStatement("editor.$L($S, " + valueL + ")", putMethod, element.getSimpleName(),
          element.getSimpleName());
    }

    result.addStatement("editor.apply()");

    return result.build();
  }

  private MethodSpec createPreferenceGetMethod(TypeSpec.Builder preferenceClass,
      TypeName targetType, List<Element> fieldElements) {
    MethodSpec.Builder result = MethodSpec.methodBuilder("get")
        .addAnnotation(Override.class)
        .addModifiers(PUBLIC)
        .addParameter(CONTEXT, "context");

    result.addStatement(
        "$T sharedPreferences = context.getSharedPreferences($S, Context.MODE_PRIVATE)",
        SHARED_PREFERENCES, targetType.toString());
    result.addStatement("$T obj = new $T()", targetType, targetType);

    for (Element element : fieldElements) {
      TypeName fieldTypeName = TypeName.get(element.asType());
      String method = "";
      String defaultValue = "0";
      String cast = "";
      String value = "$L sharedPreferences.$L($S,$L)";
      if (TypeName.get(String.class).equals(fieldTypeName)) {
        method = METHOD_GET_STRING;
        defaultValue = null;
      } else if (TypeName.BOOLEAN.equals(fieldTypeName)) {
        method = "getBoolean";
        defaultValue = "false";
      } else if (TypeName.FLOAT.equals(fieldTypeName)) {
        method = "getFloat";
      } else if (TypeName.INT.equals(fieldTypeName)) {
        method = METHOD_GET_Int;
      } else if (TypeName.BYTE.equals(fieldTypeName)) {
        method = METHOD_GET_Int;
        value = "($L) sharedPreferences.$L($S,$L)";
        cast = "byte";
      } else if (TypeName.SHORT.equals(fieldTypeName)) {
        method = METHOD_GET_Int;
        value = "($L) sharedPreferences.$L($S,$L)";
        cast = "short";
      } else if (TypeName.CHAR.equals(fieldTypeName)) {
        method = METHOD_GET_Int;
        value = "($L) sharedPreferences.$L($S,$L)";
        cast = "char";
      } else if (TypeName.LONG.equals(fieldTypeName)) {
        method = "getLong";
      } else if (TypeName.DOUBLE.equals(fieldTypeName)) {
        method = "getLong";
        value = "$L Double.longBitsToDouble(sharedPreferences.$L($S,$L))";
      } else {
        continue;
      }
      result.addStatement("obj.$N = " + value, element.getSimpleName(), cast, method,
          element.getSimpleName(), defaultValue);
    }
    result.addStatement("return obj").returns(targetType);
    return result.build();
  }

  private void logParsingError(Element element, Class<? extends Annotation> annotation,
      Exception e) {
    StringWriter stackTrace = new StringWriter();
    e.printStackTrace(new PrintWriter(stackTrace));
    error(element, "Unable to parse @%s shared.\n\n%s", annotation.getSimpleName(), stackTrace);
  }

  private void error(Element element, String message, Object... args) {
    printMessage(Diagnostic.Kind.ERROR, element, message, args);
  }

  private void note(Element element, String message, Object... args) {
    printMessage(Diagnostic.Kind.NOTE, element, message, args);
  }

  private void printMessage(Diagnostic.Kind kind, Element element, String message, Object[] args) {
    if (args.length > 0) {
      message = String.format(message, args);
    }

    processingEnv.getMessager().printMessage(kind, message, element);
  }

  @Override public Set<String> getSupportedAnnotationTypes() {
    //Set<Class<? extends Annotation>> annotations = new LinkedHashSet<>();
    //annotations.add(SharedPreferenceAble.class);
    //return annotations;
    return Collections.singleton(SharedPreferenceAble.class.getCanonicalName());
  }
}
