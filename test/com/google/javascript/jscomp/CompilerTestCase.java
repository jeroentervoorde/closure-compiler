/*
 * Copyright 2006 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.javascript.jscomp.testing.JSErrorSubject.assertError;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.ForOverride;
import com.google.javascript.jscomp.AbstractCompiler.MostRecentTypechecker;
import com.google.javascript.jscomp.CompilerOptions.LanguageMode;
import com.google.javascript.jscomp.testing.BlackHoleErrorManager;
import com.google.javascript.jscomp.type.ReverseAbstractInterpreter;
import com.google.javascript.jscomp.type.SemanticReverseAbstractInterpreter;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.testing.BaseJSTypeTestCase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import junit.framework.TestCase;

/**
 * <p>Base class for testing JS compiler classes that change
 * the node tree of a compiled JS input.</p>
 *
 * <p>Pulls in shared functionality from different test cases. Also supports
 * node tree comparison for input and output (instead of string comparison),
 * which makes it easier to write tests b/c you don't have to get the syntax
 * exactly correct to the spacing.</p>
 *
 */
public abstract class CompilerTestCase extends TestCase {
  protected static final Joiner LINE_JOINER = Joiner.on('\n');

  // TODO(sdh): Remove this option if there's never a reason to turn it on.
  private final boolean emitUseStrict = false;

  /** Externs for the test */
  private final List<SourceFile> externsInputs;

  /** Whether to compare input and output as trees instead of strings */
  private boolean compareAsTree;

  /** Whether to parse type info from JSDoc comments */
  private boolean parseTypeInfo;

  /** Whether to take JSDoc into account when comparing ASTs. */
  private boolean compareJsDoc;

  /** Whether we check warnings without source information. */
  private boolean allowSourcelessWarnings;

  /** True iff closure pass runs before pass being tested. */
  private boolean closurePassEnabled;

  /** Whether the closure pass is run on the expected JS. */
  private boolean closurePassEnabledForExpected;

  /** Whether to rewrite Closure code before the test is run. */
  private boolean rewriteClosureCode;

  /**
   * If true, run type checking together with the pass being tested. A separate
   * flag controls whether type checking runs before or after the pass.
   */
  private boolean typeCheckEnabled;

  /**
   * If true, run NTI together with the pass being tested. A separate
   * flag controls whether NTI runs before or after the pass.
   */
  private boolean newTypeInferenceEnabled;

  /**
   * If true performs the test using multistage compilation.
   */
  private boolean multistageCompilation;

  /** Whether to test the compiler pass before the type check. */
  private boolean runTypeCheckAfterProcessing;

  /** Whether to test the compiler pass before NTI. */
  private boolean runNTIAfterProcessing;

  /** Whether to scan externs for property names. */
  private boolean gatherExternPropertiesEnabled;

  /**
   * Whether the Normalize pass runs before pass being tested and
   * whether the expected JS strings should be normalized.
   */
  private boolean normalizeEnabled;

  private boolean polymerPass;

  /** Whether the tranpilation passes runs before pass being tested. */
  private boolean transpileEnabled;

  /** Whether we run InferConsts before checking. */
  private boolean inferConsts;

  /** Whether we run CheckAccessControls after the pass being tested. */
  private boolean checkAccessControls;

  /** Whether to check that all line number information is preserved. */
  private boolean checkLineNumbers;

  /** Whether to check that changed scopes are marked as changed */
  private boolean checkAstChangeMarking;

  /** Whether we expect parse warnings in the current test. */
  private boolean expectParseWarningsThisTest;

  /**
   * An expected symbol table error. Only useful for testing the
   * symbol table error-handling.
   */
  private DiagnosticType expectedSymbolTableError;

  /**
   * Whether the MarkNoSideEffectsCalls pass runs before the pass being tested
   */
  private boolean markNoSideEffects;

  /**
   * Whether the PureFunctionIdentifier pass runs before the pass being tested
   */
  private boolean computeSideEffects;

  /** The most recently used Compiler instance. */
  private Compiler lastCompiler;

  /**
   * Whether to accept ES6, ES5 or ES3 source.
   */
  private LanguageMode acceptedLanguage;

  private LanguageMode languageOut;

  /**
   * Whether externs changes should be allowed for this pass.
   */
  private boolean allowExternsChanges;

  /**
   * Whether the AST should be validated.
   */
  private boolean astValidationEnabled;

  private String filename = "testcode";

  private final Set<DiagnosticType> ignoredWarnings = new HashSet<>();

  /** Whether {@link #setUp} has run. */
  private boolean setUpRan = false;

  static final String ACTIVE_X_OBJECT_DEF =
      LINE_JOINER.join(
          "/**",
          " * @param {string} progId",
          " * @param {string=} opt_location",
          " * @constructor",
          " * @see http://msdn.microsoft.com/en-us/library/7sw4ddf8.aspx",
          " */",
          "function ActiveXObject(progId, opt_location) {}");

  /** A minimal set of externs, consisting of only those needed for NTI not to blow up. */
  static final String MINIMAL_EXTERNS =
      LINE_JOINER.join(
          "/**",
          " * @constructor",
          " * @param {*=} opt_value",
          " * @return {!Object}",
          " */",
          "function Object(opt_value) {}",
          "/**",
          " * @constructor",
          " * @param {...*} var_args",
          " */",
          "function Function(var_args) {}",
          "/**",
          " * @constructor",
          " * @param {*=} arg",
          " * @return {string}",
          " */",
          "function String(arg) {}",
          "/**",
          " * @interface",
          " * @template VALUE",
          " */",
          "function Iterable() {}",
          "/**",
          " * @interface",
          " * @template T",
          " */",
          "function Iterator() {}",
          "/**",
          " * @return {T}",
          " */",
          "Iterator.prototype.next = function() {}",
          "/**",
          " * @interface",
          " * @template VALUE",
          " */",
          "function IteratorIterable() {}",
          "/**",
          " * @interface",
          " * @template KEY1, VALUE1",
          " */",
          "function IObject() {};",
          "/**",
          " * @record",
          " * @extends IObject<number, VALUE2>",
          " * @template VALUE2",
          " */",
          "function IArrayLike() {};",
          "/**",
          " * @template T",
          " * @constructor ",
          " * @implements {IArrayLike<T>} ",
          " * @implements {Iterable<T>}",
          " * @param {...*} var_args",
          " * @return {!Array.<?>}",
          " */",
          "function Array(var_args) {}");

  /** A default set of externs for testing. */
  public static final String DEFAULT_EXTERNS =
      LINE_JOINER.join(
          MINIMAL_EXTERNS,
          "/**",
          " * @type{number}",
          " */",
          "IArrayLike.prototype.length;",
          "/** @return {string} */",
          "Object.prototype.toString = function() {};",
          "/**",
          " * @param {*} propertyName",
          " * @return {boolean}",
          " */",
          "Object.prototype.hasOwnProperty = function(propertyName) {};",
          "/** @type {?Function} */ Object.prototype.constructor;",
          "Object.defineProperties = function(obj, descriptors) {};",
          "/** @type {!Function} */ Function.prototype.apply;",
          "/** @type {!Function} */ Function.prototype.bind;",
          "/** @type {!Function} */ Function.prototype.call;",
          "/** @type {number} */",
          "Function.prototype.length;",
          "/** @type {string} */",
          "Function.prototype.name;",
          "/** @param {number} sliceArg */",
          "String.prototype.slice = function(sliceArg) {};",
          "/**",
          " * @this {?String|string}",
          " * @param {?} regex",
          " * @param {?} str",
          " * @param {string=} opt_flags",
          " * @return {string}",
          " */",
          "String.prototype.replace = function(regex, str, opt_flags) {};",
          "/** @type {number} */ String.prototype.length;",
          "/**",
          " * @constructor",
          " * @param {*=} arg",
          " * @return {number}",
          " */",
          "function Number(arg) {}",
          "/**",
          " * @constructor",
          " * @param {*=} arg",
          " * @return {boolean}",
          " */",
          "function Boolean(arg) {}",
          "/** @type {number} */ Array.prototype.length;",
          "/**",
          " * @param {...T} var_args",
          " * @return {number} The new length of the array.",
          " * @this {{length: number}|!Array.<T>}",
          " * @template T",
          " * @modifies {this}",
          " */",
          "Array.prototype.push = function(var_args) {};",
          "/**",
          " * @this {IArrayLike<T>}",
          " * @return {T}",
          " * @template T",
          " */",
          "Array.prototype.shift = function() {};",
          "/**",
          " * @param {?function(this:S, T, number, !Array<T>): ?} callback",
          " * @param {S=} opt_thisobj",
          " * @this {?IArrayLike<T>|string}",
          " * @template T,S",
          " * @return {undefined}",
          " */",
          "Array.prototype.forEach = function(callback, opt_thisobj) {};",
          "/**",
          " * @param {?function(this:S, T, number, !Array<T>): ?} callback",
          " * @param {S=} opt_thisobj",
          " * @return {!Array<T>}",
          " * @this {?IArrayLike<T>|string}",
          " * @template T,S",
          " */",
          "Array.prototype.filter = function(callback, opt_thisobj) {};",
          "/**",
          " * @constructor",
          " * @template T",
          " * @implements {IArrayLike<T>}",
          " */",
          "function Arguments() {}",
          "/** @type {number} */",
          "Arguments.prototype.length;",
          "/**",
          " * @constructor",
          " * @param {*=} opt_pattern",
          " * @param {*=} opt_flags",
          " * @return {!RegExp}",
          " * @nosideeffects",
          " */",
          "function RegExp(opt_pattern, opt_flags) {}",
          "/**",
          " * @param {*} str The string to search.",
          " * @return {?Array<string>}",
          " */",
          "RegExp.prototype.exec = function(str) {};",
          "/**",
          " * @constructor",
          " */",
          "function ObjectPropertyDescriptor() {}",
          "/** @type {*} */",
          "ObjectPropertyDescriptor.prototype.value;",
          "/**",
          " * @param {!Object} obj",
          " * @param {string} prop",
          " * @return {!ObjectPropertyDescriptor|undefined}",
          " * @nosideeffects",
          " */",
          "Object.getOwnPropertyDescriptor = function(obj, prop) {};",
          "/**",
          " * @param {!Object} obj",
          " * @param {string} prop",
          " * @param {!Object} descriptor",
          " * @return {!Object}",
          " */",
          "Object.defineProperty = function(obj, prop, descriptor) {};",
          "/** @type {?} */ var unknown;", // For producing unknowns in tests.
          "/** @typedef {?} */ var symbol;", // TODO(sdh): remove once primitive 'symbol' supported
          "/** @constructor */ function Symbol() {}",
          "/** @const {!symbol} */ Symbol.iterator;",
          ACTIVE_X_OBJECT_DEF);

  /**
   * Constructs a test.
   *
   * @param externs Externs JS as a string
   * @param compareAsTree True to compare output & expected as a node tree.
   *     99% of the time you want to compare as a tree. There are a few
   *     special cases where you don't, like if you want to test the code
   *     printing of "unnatural" syntax trees. For example,
   *
   * <pre>
   * IF
   *   IF
   *     STATEMENT
   * ELSE
   *   STATEMENT
   * </pre>
   */
  protected CompilerTestCase(String externs) {
    this.externsInputs = ImmutableList.of(SourceFile.fromCode("externs", externs));
  }

  /**
   * Constructs a test. Uses AST comparison and no externs.
   */
  protected CompilerTestCase() {
    this("");
  }

  // Overridden here so that we can easily find all classes that override.
  @Override
  protected void setUp() throws Exception {
    super.setUp();

    // TODO(sdh): Initialize *all* the options here, but first we must ensure no subclass
    // is changing them in the constructor, rather than in their own setUp method.
    this.acceptedLanguage = LanguageMode.ECMASCRIPT_2017;
    this.allowExternsChanges = false;
    this.allowSourcelessWarnings = false;
    this.astValidationEnabled = true;
    this.checkAccessControls = false;
    this.checkAstChangeMarking = true;
    this.checkLineNumbers = true;
    this.closurePassEnabled = false;
    this.closurePassEnabledForExpected = false;
    this.compareAsTree = true;
    this.compareJsDoc = true;
    this.computeSideEffects = false;
    this.expectParseWarningsThisTest = false;
    this.expectedSymbolTableError = null;
    this.gatherExternPropertiesEnabled = false;
    this.inferConsts = false;
    this.languageOut = LanguageMode.ECMASCRIPT5;
    this.markNoSideEffects = false;
    this.multistageCompilation = true;
    this.newTypeInferenceEnabled = false;
    this.normalizeEnabled = false;
    this.parseTypeInfo = false;
    this.polymerPass = false;
    this.rewriteClosureCode = false;
    this.runNTIAfterProcessing = false;
    this.runTypeCheckAfterProcessing = false;
    this.transpileEnabled = false;
    this.typeCheckEnabled = false;

    this.setUpRan = true;
  }

  @Override
  protected void tearDown() throws Exception {
    super.tearDown();
    this.setUpRan = false;
  }

  /**
   * Gets the compiler pass instance to use for a test.
   *
   * @param compiler The compiler
   * @return The pass to test
   */
  protected abstract CompilerPass getProcessor(Compiler compiler);

  /**
   * Gets the compiler options to use for this test. Use getProcessor to
   * determine what passes should be run.
   */
  protected CompilerOptions getOptions() {
    return getOptions(new CompilerOptions());
  }

  /**
   * Gets the compiler options to use for this test. Use getProcessor to
   * determine what passes should be run.
   */
  protected CompilerOptions getOptions(CompilerOptions options) {
    options.setLanguageIn(acceptedLanguage);
    options.setEmitUseStrict(emitUseStrict);
    options.setLanguageOut(languageOut);

    // This doesn't affect whether checkSymbols is run--it just affects
    // whether variable warnings are filtered.
    options.setCheckSymbols(true);

    options.setWarningLevel(DiagnosticGroups.MISSING_PROPERTIES, CheckLevel.WARNING);
    options.setWarningLevel(DiagnosticGroups.INVALID_CASTS, CheckLevel.WARNING);
    if (!ignoredWarnings.isEmpty()) {
      options.setWarningLevel(
          new DiagnosticGroup(ignoredWarnings.toArray(new DiagnosticType[0])), CheckLevel.OFF);
    }
    options.setCodingConvention(getCodingConvention());
    options.setPolymerVersion(1);
    return options;
  }

  @ForOverride
  protected CodingConvention getCodingConvention() {
    return new GoogleCodingConvention();
  }

  /**
   * Enables parsing type info from JSDoc comments. This sets the compiler option,
   * but does not actually run the type checking pass.
   */
  protected final void enableParseTypeInfo() {
    checkState(this.setUpRan, "Attempted to configure before running setUp().");
    this.parseTypeInfo = true;
  }

  /** Turns off taking JSDoc into account when comparing ASTs. */
  protected final void disableCompareJsDoc() {
    checkState(this.setUpRan, "Attempted to configure before running setUp().");
    this.compareJsDoc = false;
  }

  /** Moves OTI type checking to occur after the processor, instead of before. */
  protected final void enableRunTypeCheckAfterProcessing() {
    checkState(this.setUpRan, "Attempted to configure before running setUp().");
    this.runTypeCheckAfterProcessing = true;
  }

  /** Moves NTI type checking to occur after the processor, instead of before. */
  protected final void enableRunNTIAfterProcessing() {
    checkState(this.setUpRan, "Attempted to configure before running setUp().");
    this.runNTIAfterProcessing = true;
  }

  public final void setFilename(String filename) {
    checkState(this.setUpRan, "Attempted to configure before running setUp().");
    this.filename = filename;
  }

  public final String getFilename() {
    return filename;
  }

  /**
   * Returns the number of times the pass should be run before results are
   * verified.
   */
  @ForOverride
  protected int getNumRepetitions() {
    // Since most compiler passes should be idempotent, we run each pass twice
    // by default.
    return 2;
  }

  /** Adds the given DiagnosticTypes to the set of warnings to ignore. */
  protected final void ignoreWarnings(DiagnosticType... warnings) {
    checkState(this.setUpRan, "Attempted to configure before running setUp().");
    ignoredWarnings.addAll(Arrays.asList(warnings));
  }

  /** Adds the given DiagnosticGroups to the set of warnings to ignore. */
  protected final void ignoreWarnings(DiagnosticGroup... warnings) {
    checkState(this.setUpRan, "Attempted to configure before running setUp().");
    for (DiagnosticGroup group : warnings) {
      ignoredWarnings.addAll(group.getTypes());
    }
  }

  /** Expect warnings without source information. */
  final void allowSourcelessWarnings() {
    checkState(this.setUpRan, "Attempted to configure before running setUp().");
    allowSourcelessWarnings = true;
  }

  /** The most recently used JSComp instance. */
  Compiler getLastCompiler() {
    return lastCompiler;
  }

  /**
   * What language to allow in source parsing. Also sets the output language.
   */
  protected final void setAcceptedLanguage(LanguageMode lang) {
    checkState(this.setUpRan, "Attempted to configure before running setUp().");
    setLanguage(lang, lang);
  }

  /**
   * Sets the input and output language modes..
   */
  protected final void setLanguage(LanguageMode langIn, LanguageMode langOut) {
    this.acceptedLanguage = langIn;
    setLanguageOut(langOut);
  }

  protected final void setLanguageOut(LanguageMode acceptedLanguage) {
    checkState(this.setUpRan, "Attempted to configure before running setUp().");
    this.languageOut = acceptedLanguage;
  }

  /**
   * Whether to run InferConsts before passes
   */
  protected final void enableInferConsts() {
    checkState(this.setUpRan, "Attempted to configure before running setUp().");
    this.inferConsts = true;
  }

  /**
   * Enables running CheckAccessControls after the pass being tested (and checking types).
   */
  protected final void enableCheckAccessControls() {
    checkState(this.setUpRan, "Attempted to configure before running setUp().");
    this.checkAccessControls = true;
  }

  /**
   * Allow externs to change.
   */
  protected final void allowExternsChanges() {
    checkState(this.setUpRan, "Attempted to configure before running setUp().");
    this.allowExternsChanges = true;
  }

  public final void enablePolymerPass() {
    checkState(this.setUpRan, "Attempted to configure before running setUp().");
    polymerPass = true;
  }

  /**
   * Perform type checking before running the test pass. This will check
   * for type errors and annotate nodes with type information.
   *
   * @see TypeCheck
   */
  public final void enableTypeCheck() {
    checkState(this.setUpRan, "Attempted to configure before running setUp().");
    typeCheckEnabled = true;
  }

  // Run the new type inference after the test pass. Useful for testing passes
  // that rewrite the AST prior to typechecking, eg, AngularPass or PolymerPass.
  public void enableNewTypeInference() {
    checkState(this.setUpRan, "Attempted to configure before running setUp().");
    this.newTypeInferenceEnabled = true;
  }

  /**
   * Run using multistage compilation.
   */
  public final void enableMultistageCompilation() {
    checkState(this.setUpRan, "Attempted to configure before running setUp().");
    multistageCompilation = true;
  }

  /**
   * Run using singlestage compilation.
   */
  public final void disableMultistageCompilation() {
    checkState(this.setUpRan, "Attempted to configure before running setUp().");
    multistageCompilation = false;
  }

  /**
   * Disable checking to make sure that line numbers were preserved.
   */
  public final void disableLineNumberCheck() {
    checkState(this.setUpRan, "Attempted to configure before running setUp().");
    checkLineNumbers = false;
  }

  /**
   * @param newVal Whether to validate AST change marking.
   */
  public final void disableValidateAstChangeMarking() {
    checkState(this.setUpRan, "Attempted to configure before running setUp().");
    checkAstChangeMarking = false;
  }

  /**
   * Do not run type checking before running the test pass.
   *
   * @see TypeCheck
   */
  public final void disableTypeCheck() {
    checkState(this.setUpRan, "Attempted to configure before running setUp().");
    typeCheckEnabled = false;
  }

  public final void disableNewTypeInference() {
    checkState(this.setUpRan, "Attempted to configure before running setUp().");
    this.newTypeInferenceEnabled = false;
  }

  /**
   * Process closure library primitives.
   */
  protected final void enableClosurePass() {
    checkState(this.setUpRan, "Attempted to configure before running setUp().");
    closurePassEnabled = true;
  }

  protected final void enableClosurePassForExpected() {
    checkState(this.setUpRan, "Attempted to configure before running setUp().");
    closurePassEnabledForExpected = true;
  }

  /**
   * Rewrite Closure code before the test is run.
   */
  final void enableRewriteClosureCode() {
    checkState(this.setUpRan, "Attempted to configure before running setUp().");
    rewriteClosureCode = true;
  }

  /**
   * Don't rewrite Closure code before the test is run.
   */
  final void disableRewriteClosureCode() {
    checkState(this.setUpRan, "Attempted to configure before running setUp().");
    rewriteClosureCode = false;
  }

  /**
   * Perform AST normalization before running the test pass, and anti-normalize
   * after running it.
   *
   * @see Normalize
   */
  protected final void enableNormalize() {
    checkState(this.setUpRan, "Attempted to configure before running setUp().");
    this.normalizeEnabled = true;
  }

  /**
   * Perform AST transpilation before running the test pass.
   */
  protected final void enableTranspile() {
    checkState(this.setUpRan, "Attempted to configure before running setUp().");
    transpileEnabled = true;
  }

  /**
   * Don't perform AST normalization before running the test pass.
   * @see Normalize
   */
  protected final void disableNormalize() {
    checkState(this.setUpRan, "Attempted to configure before running setUp().");
    normalizeEnabled = false;
  }

  /**
   * Run the MarkSideEffectCalls pass before running the test pass.
   *
   * @see MarkNoSideEffectCalls
   */
  final void enableMarkNoSideEffects() {
    checkState(this.setUpRan, "Attempted to configure before running setUp().");
    markNoSideEffects = true;
  }

  /**
   * Run the PureFunctionIdentifier pass before running the test pass.
   *
   * @see MarkNoSideEffectCalls
   */
  final void enableComputeSideEffects() {
    checkState(this.setUpRan, "Attempted to configure before running setUp().");
    computeSideEffects = true;
  }

  /**
   * Scan externs for properties that should not be renamed.
   */
  final void enableGatherExternProperties() {
    checkState(this.setUpRan, "Attempted to configure before running setUp().");
    gatherExternPropertiesEnabled = true;
  }

  /**
   * Disable validating the AST after each run of the pass.
   */
  protected final void disableAstValidation() {
    checkState(this.setUpRan, "Attempted to configure before running setUp().");
    astValidationEnabled = false;
  }

  /**
   * Disable comparing the expected output as a tree or string.
   */
  protected final void disableCompareAsTree() {
    checkState(this.setUpRan, "Attempted to configure before running setUp().");
    this.compareAsTree = false;
  }

  /** Whether we should ignore parse warnings for the current test method. */
  protected final void setExpectParseWarningsThisTest() {
    checkState(this.setUpRan, "Attempted to configure before running setUp().");
    expectParseWarningsThisTest = true;
  }

  /** Returns a newly created TypeCheck. */
  private static TypeCheck createTypeCheck(Compiler compiler) {
    ReverseAbstractInterpreter rai =
        new SemanticReverseAbstractInterpreter(compiler.getTypeRegistry());
    compiler.setMostRecentTypechecker(MostRecentTypechecker.OTI);
    return new TypeCheck(compiler, rai, compiler.getTypeRegistry());
  }

  static void runNewTypeInference(Compiler compiler, Node externs, Node js) {
    GlobalTypeInfo gti = compiler.getSymbolTable();
    gti.process(externs, js);
    NewTypeInference nti = new NewTypeInference(compiler);
    nti.process(externs, js);
  }

  /**
   * Verifies that the compiler pass's JS output matches the expected output.
   *
   * @param js Input
   * @param expected Expected JS output
   */
  public void test(String js, String expected) {
    test(srcs(js), expected(expected));
  }

  /**
   * Verifies that the compiler generates the given error for the given input.
   *
   * @param js Input
   * @param error Expected error
   */
  public void testError(String js, DiagnosticType error) {
    test(srcs(js), error(error));
  }

  /**
   * Verifies that the compiler generates the given error for the given input.
   *
   * @param js Input
   * @param error Expected error
   */
  public void testError(String externs, String js, DiagnosticType error) {
    test(externs(externs), srcs(js), error(error));
  }

  /**
   * Verifies that the compiler generates the given error and description for the given input.
   */
  public void testError(String js, DiagnosticType error, String description) {
    assertNotNull(error);
    test(srcs(js), error(error, description));
  }

  /**
   * Verifies that the compiler generates the given error and description for the given input.
   */
  public void testError(Sources srcs, ErrorDiagnostic error) {
    assertNotNull(error);
    test(srcs, error);
  }

  /**
   * Verifies that the compiler generates the given error and description for the given input.
   */
  public void testError(Externs externs, Sources srcs, ErrorDiagnostic error) {
    assertNotNull(error);
    test(externs, srcs, error);
  }

  /**
   * Verifies that the compiler generates the given error for the given input.
   *
   * @param js Input
   * @param error Expected error
   */
  public void testError(String[] js, DiagnosticType error) {
    assertNotNull(error);
    test(srcs(js), error(error));
  }

  /**
   * Verifies that the compiler generates the given warning for the given input.
   */
  public void testError(List<SourceFile> inputs, DiagnosticType warning) {
    assertNotNull(warning);
    test(srcs(inputs), error(warning));
  }

  /**
   * Verifies that the compiler generates the given warning for the given input.
   */
  public void testError(List<SourceFile> inputs, DiagnosticType warning, String description) {
    assertNotNull(warning);
    test(srcs(inputs), error(warning, description));
  }

  /**
   * Verifies that the compiler generates the given warning for the given input.
   *
   * @param js Input
   * @param warning Expected warning
   */
  public void testWarning(String js, DiagnosticType warning) {
    assertNotNull(warning);
    test(srcs(js), warning(warning));
  }

  /**
   * Verifies that the compiler generates the given warning for the given input.
   *
   * @param srcs Inputs
   * @param warning Expected warning
   */
  public void testWarning(Sources srcs, WarningDiagnostic warning) {
    assertNotNull(warning);
    test(srcs, warning);
  }

  /**
   * Verifies that the compiler generates the given warning for the given input.
   *
   * @param externs The externs
   * @param srcs The input
   * @param warning Expected warning
   */
  public void testWarning(Externs externs, Sources srcs, WarningDiagnostic warning) {
    assertNotNull(warning);
    test(externs, srcs, warning);
  }

  /**
   * Verifies that the compiler generates the given warning for the given input.
   *
   * @param js Input
   * @param warning Expected warning
   */
  public void testWarning(String externs, String js, DiagnosticType warning) {
    assertNotNull(warning);
    test(externs(externs), srcs(js), warning(warning));
  }

  /**
   * Verifies that the compiler generates the given warning for the given input.
   */
  public void testWarning(String[] js, DiagnosticType warning) {
    assertNotNull(warning);
    test(srcs(js), warning(warning));
  }

  /**
   * Verifies that the compiler generates the given warning for the given input.
   */
  public void testWarning(List<SourceFile> inputs, DiagnosticType warning) {
    assertNotNull(warning);
    test(srcs(inputs), warning(warning));
  }

  /**
   * Verifies that the compiler generates the given warning and description for the given input.
   *
   * @param js Input
   * @param warning Expected warning
   */
  public void testWarning(String js, DiagnosticType warning, String description) {
    assertNotNull(warning);
    test(srcs(js), warning(warning, description));
  }

  /**
   * Verifies that the compiler generates the given warning for the given input.
   */
  public void testWarning(List<SourceFile> inputs, DiagnosticType warning, String description) {
    assertNotNull(warning);
    test(srcs(inputs), warning(warning, description));
  }

  /**
   * Verifies that the compiler generates the given warning for the given input.
   */
  public void testWarning(String externs, String js, DiagnosticType warning, String description) {
    assertNotNull(warning);
    test(externs(externs), srcs(js), warning(warning, description));
  }

  /**
   * Verifies that the compiler generates no warnings for the given input.
   *
   * @param js Input
   */
  public void testNoWarning(String js) {
    test(srcs(js));
  }

  /**
   * Verifies that the compiler generates no warnings for the given input.
   *
   * @param js Input
   */
  public void testNoWarning(Sources srcs) {
    test(srcs);
  }

  /**
   * Verifies that the compiler generates no warnings for the given input.
   *
   * @param js Input
   */
  public void testNoWarning(String externs, String js) {
    test(externs(externs), srcs(js));
  }

  /**
   * Verifies that the compiler generates no warnings for the given input.
   */
  public void testNoWarning(String[] js) {
    test(srcs(js));
  }

  /**
   * Verifies that the compiler generates no warnings for the given input.
   */
  public void testNoWarning(List<SourceFile> inputs) {
    test(srcs(inputs));
  }

  /**
   * Verifies that the compiler pass's JS output matches the expected output
   * and (optionally) that an expected warning is issued. Or, if an error is
   * expected, this method just verifies that the error is encountered.
   *
   * @param js Input
   * @param expected Expected output, or null if an error is expected
   * @param error Expected error, or null if no error is expected
   * @param diagnostic Expected warning or error
   */
  protected void test(String js, String expected, Diagnostic diagnostic) {
    test(externs(externsInputs), srcs(js), expected(expected), diagnostic);
  }

  /**
   * Verifies that the compiler pass's JS output matches the expected output
   * and (optionally) that an expected warning is issued. Or, if an error is
   * expected, this method just verifies that the error is encountered.
   *
   * @param externs the externs
   * @param js Input
   * @param expected Expected output, or null if an error is expected
   * @param error Expected error, or null if no error is expected
   * @param diagnostic Expected warning or error
   */
  protected void test(String externs, String js, String expected, Diagnostic diagnostic) {
    test(externs(externs), srcs(js), expected(expected), diagnostic);
  }

  protected void testInternal(
      Externs externs,
      Sources inputs,
      Expected expected,
      Diagnostic diagnostic) {

    Compiler compiler = createCompiler();
    lastCompiler = compiler;

    CompilerOptions options = getOptions();

    options.setCheckTypes(parseTypeInfo || this.typeCheckEnabled);
    compiler.init(externs.externs, inputs.sources, options);

    if (this.typeCheckEnabled) {
      BaseJSTypeTestCase.addNativeProperties(compiler.getTypeRegistry());
    }

    testInternal(compiler, inputs, expected, diagnostic);
  }

  private static List<SourceFile> maybeCreateSources(String name, String srcText) {
    if (srcText != null) {
      return ImmutableList.of(SourceFile.fromCode(name, srcText));
    }
    return null;
  }

  /**
   * Verifies that the compiler pass's JS output matches the expected output.
   *
   * @param js Inputs
   * @param expected Expected JS output
   */
  public void test(String externs, String js, String expected) {
    test(externs(externs), srcs(js), expected(expected));
  }

  /**
   * Verifies that the compiler pass's JS output matches the expected output.
   *
   * @param js Inputs
   * @param expected Expected JS output
   */
  public void test(String[] js, String[] expected) {
    test(srcs(js), expected(expected));
  }

  /**
   * Verifies that the compiler pass's JS output matches the expected output.
   *
   * @param js Inputs
   * @param expected Expected JS output
   */
  public void test(List<SourceFile> js, List<SourceFile> expected) {
    test(srcs(js), expected(expected));
  }

  private List<SourceFile> createSources(String name, String... sources) {
    if (sources == null) {
      return null;
    }
    List<SourceFile> expectedSources = new ArrayList<>();
    for (int i = 0; i < sources.length; i++) {
      expectedSources.add(SourceFile.fromCode(name + i, sources[i]));
    }
    return expectedSources;
  }

  /**
   * Verifies that the compiler pass's JS output matches the expected output.
   *
   * @param modules Module inputs
   * @param expected Expected JS outputs (one per module)
   */
  public void test(JSModule[] modules, String[] expected) {
    test(modules, expected(expected), null);
  }

  /**
   * Verifies that the compiler pass's JS output matches the expected output
   * and (optionally) that an expected warning is issued. Or, if an error is
   * expected, this method just verifies that the error is encountered.
   *
   * @param modules Module inputs
   * @param expected Expected JS outputs (one per module)
   * @param diagnostic the warning or error expected
   */
  protected void test(
      JSModule[] modules, Expected expected, Diagnostic diagnostic) {
    Compiler compiler = createCompiler();
    lastCompiler = compiler;

    compiler.initModules(externsInputs, ImmutableList.copyOf(modules), getOptions());
    test(compiler, null, expected, diagnostic);
  }

  /**
   * Verifies that the compiler pass's JS output is the same as its input.
   *
   * @param js Input and output
   */
  public void testSame(String js) {
    test(srcs(js), expected(js));
  }

  /**
   * Verifies that the compiler pass's JS output is the same as its input
   * and (optionally) that an expected warning is issued.
   *
   * @param externs Externs input
   * @param js Input and output
   * @param warning Expected warning, or null if no warning is expected
   */
  public void testSame(String externs, String js) {
    test(externs(externs), srcs(js), expected(js));
  }

  /**
   * Verifies that the compiler pass's JS output is the same as its input
   * and (optionally) that an expected warning is issued.
   *
   * @param js Input and output
   * @param warning Expected warning, or null if no warning is expected
   */
  public void testSame(String js, DiagnosticType warning) {
    test(srcs(js), expected(js), warning(warning));
  }

  /**
   * Verifies that the compiler pass's JS output is the same as its input
   * and (optionally) that an expected warning is issued.
   *
   * @param externs Externs input
   * @param js Input and output
   * @param warning Expected warning, or null if no warning is expected
   */
  public void testSame(String externs, String js, DiagnosticType warning) {
    test(externs(externs), srcs(js), expected(js), warning(warning));
  }

  /**
   * Verifies that the compiler pass's JS output is the same as its input
   * and (optionally) that an expected warning is issued.
   *
   * @param externs Externs input
   * @param js Input and output
   * @param diag Expected error or warning, or null if none is expected

   */
  public void testSame(String externs, String js, Diagnostic diag) {
    test(externs(externs), srcs(js), diag);
  }

  /**
   * Verifies that the compiler pass's JS output is the same as its input
   * and (optionally) that an expected warning and description is issued.
   *
   * @param externs Externs input
   * @param js Input and output
   * @param warning Expected warning, or null if no warning is expected
   * @param description The description of the expected warning,
   *      or null if no warning is expected or if the warning's description
   *      should not be examined
   */
  public void testSame(String externs, String js, DiagnosticType warning, String description) {
    test(externs(externs), srcs(js), expected(js), warning(warning, description));
  }

  /**
   * Verifies that the compiler pass's JS output is the same as its input.
   *
   * @param js Inputs and outputs
   */
  public void testSame(String[] js) {
    test(srcs(js), expected(js));
  }

  /**
   * Verifies that the compiler pass's JS output is the same as its input.
   *
   * @param js Inputs and outputs
   */
  public void testSame(List<SourceFile> js) {
    test(srcs(js), expected(js));
  }

  /**
   * Verifies that the compiler pass's JS output is the same as its input,
   * and emits the given warning.
   *
   * @param js Inputs and outputs
   * @param warning Expected warning, or null if no warning is expected
   */
  public void testSameWarning(String[] js, DiagnosticType warning) {
    test(srcs(js), expected(js), warning(warning));
  }

  /**
   * Verifies that the compiler pass's JS output is the same as the input.
   *
   * @param modules Module inputs
   */
  public void testSame(JSModule[] modules) {
    testSame(modules, null);
  }

  /**
   * Verifies that the compiler pass's JS output is the same as the input.
   *
   * @param modules Module inputs
   * @param warning A warning, or null for no expected warning.
   */
  public void testSame(JSModule[] modules, DiagnosticType warning) {
    try {
      String[] expected = new String[modules.length];
      for (int i = 0; i < modules.length; i++) {
        expected[i] = "";
        for (CompilerInput input : modules[i].getInputs()) {
          expected[i] += input.getSourceFile().getCode();
        }
      }
      test(modules, expected(expected), warning(warning));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Verifies that the compiler pass's JS output matches the expected output
   * and (optionally) that an expected warning is issued. Or, if an error is
   * expected, this method just verifies that the error is encountered.
   *  @param compiler A compiler that has been initialized via
   *     {@link Compiler#init}
   * @param compiler The compiler which contains the configured inputs.
   * @param inputsObj The inputs for reference.
   * @param expected Expected output, or null if an error is expected
   * @param diagnostic Expected error or warning, or null if none expected
   */
  protected void test(
      Compiler compiler,
      Sources inputsObj,
      Expected expectedObj,
      Diagnostic diagnostic) {
    testInternal(compiler, inputsObj, expectedObj, diagnostic);
  }

  /**
   * Verifies that the compiler pass's JS output matches the expected output
   * and (optionally) that an expected warning is issued. Or, if an error is
   * expected, this method just verifies that the error is encountered.
   *  @param compiler A compiler that has been initialized via
   *     {@link Compiler#init}
   *  @param inputsObj Input source files
   *  @param expectedObj Expected outputs
   *  @param diagnostic Expected warning/error diagnostic
   */
  protected void testInternal(
      Compiler compiler,
      Sources inputsObj,  // TODO remove this parameter
      Expected expectedObj,
      Diagnostic diagnostic) {
    List<SourceFile> inputs = inputsObj != null ? inputsObj.sources : null;
    List<SourceFile> expected = expectedObj != null ? expectedObj.expected : null;
    checkState(!this.typeCheckEnabled || !this.newTypeInferenceEnabled);
    checkState(this.setUpRan, "CompilerTestCase.setUp not run: call super.setUp() from overrides.");
    RecentChange recentChange = new RecentChange();
    compiler.addChangeHandler(recentChange);

    compiler.getOptions().setNewTypeInference(this.newTypeInferenceEnabled);

    Node root = compiler.parseInputs();

    String errorMsg = LINE_JOINER.join(compiler.getErrors());
    if (root == null && expected == null
        && diagnostic != null
        && diagnostic.level == CheckLevel.ERROR) {
      // Might be an expected parse error.
      assertWithMessage("Expected one parse error, but got " + errorMsg)
          .that(compiler.getErrorCount())
          .isEqualTo(1);
      JSError actualError = compiler.getErrors()[0];
      assertWithMessage("Unexpected parse error(s): " + errorMsg)
          .that(actualError.getType())
          .isEqualTo(diagnostic.diagnostic);
      if (diagnostic.match != null) {
        assertThat(actualError.description).isEqualTo(diagnostic.match);
      }
      return;
    }
    assertWithMessage("Unexpected parse error(s): " + errorMsg).that(root).isNotNull();
    if (!expectParseWarningsThisTest) {
      assertWithMessage("Unexpected parser warning(s)").that(compiler.getWarnings()).isEmpty();
    } else {
      assertThat(compiler.getWarningCount()).isGreaterThan(0);
    }

    if (astValidationEnabled) {
      (new AstValidator(compiler)).validateRoot(root);
    }
    Node externsRoot = root.getFirstChild();
    Node mainRoot = root.getLastChild();

    // Save the tree for later comparison.
    Node rootClone = root.cloneTree();
    Node externsRootClone = rootClone.getFirstChild();
    Node mainRootClone = rootClone.getLastChild();

    int numRepetitions = getNumRepetitions();
    ErrorManager[] errorManagers = new ErrorManager[numRepetitions];
    int aggregateWarningCount = 0;
    List<JSError> aggregateWarnings = new ArrayList<>();
    boolean hasCodeChanged = false;

    for (int i = 0; i < numRepetitions; ++i) {
      if (compiler.getErrorCount() == 0) {
        errorManagers[i] = new BlackHoleErrorManager();
        compiler.setErrorManager(errorManagers[i]);

        if (polymerPass && i == 0) {
          recentChange.reset();
          new PolymerPass(compiler).process(externsRoot, mainRoot);
          hasCodeChanged = hasCodeChanged || recentChange.hasCodeChanged();
        }

        if (rewriteClosureCode && i == 0) {
          new ClosureRewriteClass(compiler).process(null, mainRoot);
          new ClosureRewriteModule(compiler, null, null).process(null, mainRoot);
          new ScopedAliases(compiler, null, CompilerOptions.NULL_ALIAS_TRANSFORMATION_HANDLER)
              .process(null, mainRoot);
          hasCodeChanged = hasCodeChanged || recentChange.hasCodeChanged();
        }

        // Only run process closure primitives once, if asked.
        if (closurePassEnabled && i == 0) {
          recentChange.reset();
          new ProcessClosurePrimitives(compiler, null, CheckLevel.ERROR, false)
              .process(externsRoot, mainRoot);
          hasCodeChanged = hasCodeChanged || recentChange.hasCodeChanged();
        }

        if (transpileEnabled && i == 0) {
          recentChange.reset();
          transpileToEs5(compiler, externsRoot, mainRoot);
          hasCodeChanged = hasCodeChanged || recentChange.hasCodeChanged();
        }

        // Only run the type checking pass once, if asked.
        // Running it twice can cause unpredictable behavior because duplicate
        // objects for the same type are created, and the type system
        // uses reference equality to compare many types.
        if (!runTypeCheckAfterProcessing && typeCheckEnabled && i == 0) {
          TypeCheck check = createTypeCheck(compiler);
          check.processForTesting(externsRoot, mainRoot);
        } else if (!this.runNTIAfterProcessing
            && this.newTypeInferenceEnabled
            && i == 0) {
          runNewTypeInference(compiler, externsRoot, mainRoot);
        }

        boolean runNormalization = normalizeEnabled && i == 0;

        if (multistageCompilation && runNormalization) {
          // Only run multistage compilation when normalizing.

          // TODO(rluble): Use the multistage pipeline for NTI and modules.
          if (!newTypeInferenceEnabled
              && inputs != null
              && compiler.getModuleGraph() == null) {
            compiler = multistageSerializeAndDeserialize(compiler, inputs, recentChange);
            root = compiler.getRoot();
            externsRoot = compiler.getExternsRoot();
            mainRoot = compiler.getJsRoot();
            lastCompiler = compiler;
          }
        }

        // Only run the normalize pass once, if asked.
        if (runNormalization) {
          normalizeActualCode(compiler, externsRoot, mainRoot);
        }

        if (inferConsts && i == 0) {
          new InferConsts(compiler).process(externsRoot, mainRoot);
        }

        if (computeSideEffects && i == 0) {
          recentChange.reset();
          PureFunctionIdentifier.Driver mark =
              new PureFunctionIdentifier.Driver(compiler, null);
          mark.process(externsRoot, mainRoot);
          hasCodeChanged = hasCodeChanged || recentChange.hasCodeChanged();
        }

        if (markNoSideEffects && i == 0) {
          MarkNoSideEffectCalls mark = new MarkNoSideEffectCalls(compiler);
          mark.process(externsRoot, mainRoot);
        }

        if (gatherExternPropertiesEnabled && i == 0) {
          (new GatherExternProperties(compiler)).process(externsRoot, mainRoot);
        }

        recentChange.reset();

        ChangeVerifier changeVerifier = null;
        if (checkAstChangeMarking) {
          changeVerifier = new ChangeVerifier(compiler);
          changeVerifier.snapshot(mainRoot);
        }

        getProcessor(compiler).process(externsRoot, mainRoot);

        if (checkAstChangeMarking) {
          // TODO(johnlenz): add support for multiple passes in getProcessor so that we can
          // check the AST marking after each pass runs.
          // Verify that changes to the AST are properly marked on the AST.
          changeVerifier.checkRecordedChanges(mainRoot);
        }

        if (astValidationEnabled) {
          (new AstValidator(compiler)).validateRoot(root);
        }
        if (checkLineNumbers) {
          (new LineNumberCheck(compiler)).process(externsRoot, mainRoot);
        }

        if (runTypeCheckAfterProcessing && typeCheckEnabled && i == 0) {
          TypeCheck check = createTypeCheck(compiler);
          check.processForTesting(externsRoot, mainRoot);
        } else if (this.runNTIAfterProcessing
            && this.newTypeInferenceEnabled
            && i == 0) {
          runNewTypeInference(compiler, externsRoot, mainRoot);
        }

        if (checkAccessControls) {
          (new CheckAccessControls(compiler, false)).process(externsRoot, mainRoot);
        }

        hasCodeChanged = hasCodeChanged || recentChange.hasCodeChanged();
        aggregateWarningCount += errorManagers[i].getWarningCount();
        Collections.addAll(aggregateWarnings, compiler.getWarnings());

        if (normalizeEnabled) {
          boolean verifyDeclaredConstants = true;
          new Normalize.VerifyConstants(compiler, verifyDeclaredConstants)
              .process(externsRoot, mainRoot);
        }
      }
    }

    if (diagnostic == null || diagnostic.level != CheckLevel.ERROR) {
      assertThat(compiler.getErrors()).isEmpty();

      // Verify the symbol table.
      ErrorManager symbolTableErrorManager = new BlackHoleErrorManager();
      compiler.setErrorManager(symbolTableErrorManager);
      Node expectedRoot = null;
      if (expected != null) {
        expectedRoot = parseExpectedJs(expected);
        expectedRoot.detach();
      }

      JSError[] stErrors = symbolTableErrorManager.getErrors();
      if (expectedSymbolTableError != null) {
        assertEquals("There should be one error.", 1, stErrors.length);
        assertError(stErrors[0]).hasType(expectedSymbolTableError);
      } else {
        assertThat(stErrors).named("symbol table errors").isEmpty();
      }

      if (diagnostic == null || diagnostic.level != CheckLevel.WARNING) {
        assertThat(aggregateWarnings).named("aggregate warnings").isEmpty();
      } else {
        assertEquals(
            "There should be one warning, repeated "
                + numRepetitions
                + " time(s). Warnings: \n"
                + LINE_JOINER.join(aggregateWarnings),
            numRepetitions,
            aggregateWarningCount);
        for (int i = 0; i < numRepetitions; ++i) {
          JSError[] warnings = errorManagers[i].getWarnings();
          JSError actual = warnings[0];
          assertError(actual).hasType(diagnostic.diagnostic);
          validateSourceLocation(actual);

          if (diagnostic.match != null) {
            assertThat(actual.description).isEqualTo(diagnostic.match);
          }
        }
      }

      // If we ran normalize on the AST, we must also run normalize on the
      // clone before checking for changes.
      if (normalizeEnabled) {
        normalizeActualCode(compiler, externsRootClone, mainRootClone);
      }

      boolean codeChange = !mainRootClone.isEquivalentWithSideEffectsTo(mainRoot);
      boolean externsChange = !externsRootClone.isEquivalentWithSideEffectsTo(externsRoot);

      // Generally, externs should not be changed by the compiler passes.
      if (externsChange && !allowExternsChanges) {
        String explanation = externsRootClone.checkTreeEquals(externsRoot);
        fail(
            "Unexpected changes to externs"
                + "\nExpected: "
                + compiler.toSource(externsRootClone)
                + "\nResult:   "
                + compiler.toSource(externsRoot)
                + "\n"
                + explanation);
      }

      if (!codeChange && !externsChange) {
        assertFalse(
            "compiler.reportCodeChange() was called " + "even though nothing changed",
            hasCodeChanged);
      } else {
        assertTrue(
            "compiler.reportCodeChange() should have been called."
                + "\nOriginal: "
                + mainRootClone.toStringTree()
                + "\nNew: "
                + mainRoot.toStringTree(),
            hasCodeChanged);
      }

      if (expected != null) {
        if (compareAsTree) {
          String explanation;
          if (compareJsDoc) {
            explanation = expectedRoot.checkTreeEqualsIncludingJsDoc(mainRoot);
          } else {
            explanation = expectedRoot.checkTreeEquals(mainRoot);
          }
          if (explanation != null) {
            String expectedAsSource = compiler.toSource(expectedRoot);
            String mainAsSource = compiler.toSource(mainRoot);
            if (expectedAsSource.equals(mainAsSource)) {
              fail("In: " + expectedAsSource + "\n" + explanation);
            } else {
              fail("\nExpected: "
                  + expectedAsSource
                  + "\nResult:   "
                  + mainAsSource
                  + "\n" + explanation);
            }
          }
        } else {
          String[] expectedSources = new String[expected.size()];
          for (int i = 0; i < expected.size(); ++i) {
            try {
              expectedSources[i] = expected.get(i).getCode();
            } catch (IOException e) {
              throw new RuntimeException("failed to get source code", e);
            }
          }
          assertThat(compiler.toSource(mainRoot)).isEqualTo(Joiner.on("").join(expectedSources));
        }
      }

      // Verify normalization is not invalidated.
      Node normalizeCheckRootClone = root.cloneTree();
      Node normalizeCheckExternsRootClone = normalizeCheckRootClone.getFirstChild();
      Node normalizeCheckMainRootClone = normalizeCheckRootClone.getLastChild();
      new PrepareAst(compiler).process(normalizeCheckExternsRootClone, normalizeCheckMainRootClone);
      String explanation = normalizeCheckMainRootClone.checkTreeEquals(mainRoot);
      assertNull(
          "Node structure normalization invalidated."
              + "\nExpected: "
              + compiler.toSource(normalizeCheckMainRootClone)
              + "\nResult:   "
              + compiler.toSource(mainRoot)
              + "\n"
              + explanation,
          explanation);

      // TODO(johnlenz): enable this for most test cases.
      // Currently, this invalidates test for while-loops, for-loop
      // initializers, and other naming.  However, a set of code
      // (Closure primitive rewrites, etc) runs before the Normalize pass,
      // so this can't be force on everywhere.
      if (normalizeEnabled) {
        new Normalize(compiler, true)
            .process(normalizeCheckExternsRootClone, normalizeCheckMainRootClone);
        explanation = normalizeCheckMainRootClone.checkTreeEquals(mainRoot);
        assertNull(
            "Normalization invalidated."
                + "\nExpected: "
                + compiler.toSource(normalizeCheckMainRootClone)
                + "\nResult:   "
                + compiler.toSource(mainRoot)
                + "\n"
                + explanation,
            explanation);
      }
    } else {
      assertNull("expected must be null if error != null", expected);
      assertEquals(
          "There should be one error of type '" + diagnostic.diagnostic.key + "' but there were: "
          + Arrays.toString(compiler.getErrors()),
          1, compiler.getErrorCount());
      JSError actualError = compiler.getErrors()[0];
      assertEquals(errorMsg, diagnostic.diagnostic, actualError.getType());
      validateSourceLocation(actualError);
      if (diagnostic.match != null) {
        assertThat(actualError.description).isEqualTo(diagnostic.match);
      }
      assertWithMessage("Some placeholders in the error message were not replaced")
          .that(actualError.description)
          .doesNotContainMatch("\\{\\d\\}");

      if (diagnostic != null && diagnostic.level == CheckLevel.WARNING) {
        String warnings = "";
        for (JSError actualWarning : compiler.getWarnings()) {
          warnings += actualWarning.description + "\n";
          assertWithMessage("Some placeholders in the warning message were not replaced")
              .that(actualWarning.description)
              .doesNotContainMatch("\\{\\d\\}");
        }
        assertEquals("There should be one warning. " + warnings, 1, compiler.getWarningCount());
        assertEquals(warnings, diagnostic.diagnostic, compiler.getWarnings()[0].getType());
      }
    }
  }

  private Compiler multistageSerializeAndDeserialize(
      Compiler compiler,
      List<SourceFile> inputs,
      CodeChangeHandler changeHandler) {
    ErrorManager errorManager = compiler.getErrorManager();
    try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
      compiler.removeChangeHandler(changeHandler);
      compiler.disableThreads();
      compiler.saveState(baos);

      try (ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray())) {
        compiler = createCompiler();
        compiler.disableThreads();
        compiler.init(externsInputs, inputs, getOptions());
        compiler.restoreState(bais);
        compiler.setErrorManager(errorManager);
        compiler.addChangeHandler(changeHandler);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return compiler;
  }

  private static void transpileToEs5(AbstractCompiler compiler, Node externsRoot, Node codeRoot) {
    List<PassFactory> factories = new ArrayList<>();
    TranspilationPasses.addEs2017Passes(factories);
    TranspilationPasses.addEs6EarlyPasses(factories);
    TranspilationPasses.addEs6LatePasses(factories);
    TranspilationPasses.addRewritePolyfillPass(factories);
    for (PassFactory factory : factories) {
      factory.create(compiler).process(externsRoot, codeRoot);
    }
  }

  private void validateSourceLocation(JSError jserror) {
    // Make sure that source information is always provided.
    if (!allowSourcelessWarnings) {
      assertTrue(
          "Missing source file name in warning: " + jserror,
          jserror.sourceName != null && !jserror.sourceName.isEmpty());
      assertTrue("Missing line number in warning: " + jserror, -1 != jserror.lineNumber);
      assertTrue("Missing char number in warning: " + jserror, -1 != jserror.getCharno());
    }
  }

  private static void normalizeActualCode(Compiler compiler, Node externsRoot, Node mainRoot) {
    Normalize normalize = new Normalize(compiler, false);
    normalize.process(externsRoot, mainRoot);
  }

  /**
   * Parses expected JS inputs and returns the root of the parse tree.
   */
  protected Node parseExpectedJs(String[] expected) {
    List<SourceFile> inputs = createSources("expected",
        expected);
    return parseExpectedJs(inputs);
  }

  /**
   * Parses expected JS inputs and returns the root of the parse tree.
   */
  protected Node parseExpectedJs(List<SourceFile> inputs) {
    Compiler compiler = createCompiler();

    compiler.init(externsInputs, inputs, getOptions());
    Node root = compiler.parseInputs();
    assertNotNull("Unexpected parse error(s): " + LINE_JOINER.join(compiler.getErrors()), root);
    Node externsRoot = root.getFirstChild();
    Node mainRoot = externsRoot.getNext();
    // Only run the normalize pass, if asked.
    if (normalizeEnabled && !compiler.hasErrors()) {
      Normalize normalize = new Normalize(compiler, false);
      normalize.process(externsRoot, mainRoot);
    }

    if (closurePassEnabled && closurePassEnabledForExpected && !compiler.hasErrors()) {
      new ProcessClosurePrimitives(compiler, null, CheckLevel.ERROR, false)
          .process(externsRoot, mainRoot);
    }

    if (rewriteClosureCode) {
      new ClosureRewriteClass(compiler).process(externsRoot, mainRoot);
      new ClosureRewriteModule(compiler, null, null).process(externsRoot, mainRoot);
      new ScopedAliases(compiler, null, CompilerOptions.NULL_ALIAS_TRANSFORMATION_HANDLER)
          .process(externsRoot, mainRoot);
    }

    if (transpileEnabled && !compiler.hasErrors()) {
      transpileToEs5(compiler, externsRoot, mainRoot);
    }
    return mainRoot;
  }

  protected void testExternChanges(String input, String expectedExtern) {
    testExternChanges("", input, expectedExtern);
  }

  protected void testExternChanges(String extern, String input, String expectedExtern) {
    testExternChanges(extern, input, expectedExtern, (DiagnosticType[]) null);
  }

  protected void testExternChanges(String input, String expectedExtern,
      DiagnosticType... warnings) {
    testExternChanges("", input, expectedExtern, warnings);
  }

  protected void testExternChanges(String extern, String input, String expectedExtern,
      DiagnosticType... warnings) {
    Compiler compiler = createCompiler();
    CompilerOptions options = getOptions();
    compiler.init(
        maybeCreateSources("extern", extern),
        maybeCreateSources("input", input),
        options);
    compiler.parseInputs();
    assertThat(compiler.hasErrors()).isFalse();

    Node externsAndJs = compiler.getRoot();
    Node root = externsAndJs.getLastChild();

    Node externs = externsAndJs.getFirstChild();

    Node expected = compiler.parseTestCode(expectedExtern);
    assertThat(compiler.getErrors()).isEmpty();

    (getProcessor(compiler)).process(externs, root);

    if (compareAsTree) {
      // Ignore and remove empty externs, so that if we start with an empty extern and only add
      // to the synthetic externs, we can still enable compareAsTree.
      if (externs.hasMoreThanOneChild()) {
        for (Node c : externs.children()) {
          if (!c.hasChildren()) {
            c.detach();
          }
        }
      }

      // Expected output parsed without implied block.
      checkState(externs.isRoot());
      checkState(compareJsDoc);
      checkState(
          externs.hasOneChild(), "Compare as tree only works when output has a single script.");
      externs = externs.getFirstChild();
      String explanation = expected.checkTreeEqualsIncludingJsDoc(externs);
      assertNull(""
          + "\nExpected: " + compiler.toSource(expected)
          + "\nResult:   " + compiler.toSource(externs)
          + "\n" + explanation,
          explanation);
    } else {
      String externsCode = compiler.toSource(externs);
      String expectedCode = compiler.toSource(expected);
      assertThat(externsCode).isEqualTo(expectedCode);
    }

    if (warnings != null) {
      String warningMessage = "";
      for (JSError actualWarning : compiler.getWarnings()) {
        warningMessage += actualWarning.description + "\n";
      }
      assertEquals("There should be " + warnings.length + " warnings. " + warningMessage,
          warnings.length, compiler.getWarningCount());
      for (int i = 0; i < warnings.length; i++) {
        DiagnosticType warning = warnings[i];
        assertEquals(warningMessage, warning, compiler.getWarnings()[i].getType());
      }
    }
  }

  protected Node parseExpectedJs(String expected) {
    return parseExpectedJs(new String[] {expected});
  }

  /**
   * Generates a list of modules from a list of inputs, such that each module
   * depends on the module before it.
   */
  static JSModule[] createModuleChain(String... inputs) {
    return createModuleChain(Arrays.asList(inputs), "i", ".js");
  }

  public static JSModule[] createModuleChain(
      List<String> inputs, String fileNamePrefix, String fileNameSuffix) {
    JSModule[] modules = createModules(inputs, fileNamePrefix, fileNameSuffix);
    for (int i = 1; i < modules.length; i++) {
      modules[i].addDependency(modules[i - 1]);
    }
    return modules;
  }

  /**
   * Generates a list of modules from a list of inputs, such that each module
   * depends on the first module.
   */
  public static JSModule[] createModuleStar(String... inputs) {
    JSModule[] modules = createModules(inputs);
    for (int i = 1; i < modules.length; i++) {
      modules[i].addDependency(modules[0]);
    }
    return modules;
  }

  /**
   * Generates a list of modules from a list of inputs, such that modules
   * form a bush formation. In a bush formation, module 2 depends
   * on module 1, and all other modules depend on module 2.
   */
  public static JSModule[] createModuleBush(String... inputs) {
    checkState(inputs.length > 2);
    JSModule[] modules = createModules(inputs);
    for (int i = 1; i < modules.length; i++) {
      modules[i].addDependency(modules[i == 1 ? 0 : 1]);
    }
    return modules;
  }

  /**
   * Generates a list of modules from a list of inputs, such that modules
   * form a tree formation. In a tree formation, module N depends on
   * module `floor(N/2)`, So the modules form a balanced binary tree.
   */
  public static JSModule[] createModuleTree(String... inputs) {
    JSModule[] modules = createModules(inputs);
    for (int i = 1; i < modules.length; i++) {
      modules[i].addDependency(modules[(i - 1) / 2]);
    }
    return modules;
  }

  /**
   * Generates a list of modules from a list of inputs. Does not generate any
   * dependencies between the modules.
   */
  public static JSModule[] createModules(String... inputs) {
    return createModules(Arrays.asList(inputs), "i", ".js");
  }

  protected static JSModule[] createModules(
      List<String> inputs, String fileNamePrefix, String fileNameSuffix) {
    JSModule[] modules = new JSModule[inputs.size()];
    for (int i = 0; i < inputs.size(); i++) {
      JSModule module = modules[i] = new JSModule("m" + i);
      module.add(SourceFile.fromCode(fileNamePrefix + i + fileNameSuffix, inputs.get(i)));
    }
    return modules;
  }

  protected Compiler createCompiler() {
    Compiler compiler = new Compiler();
    compiler.setFeatureSet(acceptedLanguage.toFeatureSet());
    return compiler;
  }

  protected void setExpectedSymbolTableError(DiagnosticType type) {
    this.expectedSymbolTableError = type;
  }

  /** Finds the first matching qualified name node in post-traversal order. */
  protected final Node findQualifiedNameNode(final String name, Node root) {
    return findQualifiedNameNodes(name, root).get(0);
  }

  /** Finds all the matching qualified name nodes in post-traversal order. */
  protected final List<Node> findQualifiedNameNodes(final String name, Node root) {
    final List<Node> matches = new ArrayList<>();
    NodeUtil.visitPostOrder(
        root,
        new NodeUtil.Visitor() {
          @Override
          public void visit(Node n) {
            if (name.equals(n.getQualifiedName())) {
              matches.add(n);
            }
          }
        },
        Predicates.<Node>alwaysTrue());
    return matches;
  }

  /** A Compiler that records requested runtime libraries, rather than injecting. */
  protected static class NoninjectingCompiler extends Compiler {

    protected final Set<String> injected = new HashSet<>();

    @Override
    Node ensureLibraryInjected(String library, boolean force) {
      injected.add(library);
      return null;
    }

    @Override
    public void saveState(OutputStream outputStream) throws IOException {
      super.saveState(outputStream);
      ObjectOutputStream out = new ObjectOutputStream(outputStream);
      out.writeObject(injected);
    }

    @Override
    public void restoreState(InputStream inputStream) throws IOException, ClassNotFoundException {
      super.restoreState(inputStream);
      ObjectInputStream in = new ObjectInputStream(inputStream);
      injected.clear();
      injected.addAll((Set<String>) in.readObject());
    }
  }

  protected String lines(String line) {
    return line;
  }

  protected String lines(String ...lines) {
    return LINE_JOINER.join(lines);
  }

  protected Sources srcs(String srcText) {
    return new Sources(maybeCreateSources(filename,  srcText));
  }

  protected Sources srcs(String[] srcTexts) {
    return new Sources(createSources("input", srcTexts));
  }

  protected Sources srcs(List<SourceFile> files) {
    return new Sources(files);
  }

  protected Expected expected(String srcText) {
    return new Expected(maybeCreateSources("expected",  srcText));
  }

  protected Expected expected(String[] srcTexts) {
    return new Expected(createSources("expected", srcTexts));
  }

  protected Expected expected(List<SourceFile> files) {
    return new Expected(files);
  }

  protected Externs externs(String externSrc) {
    return new Externs(maybeCreateSources("externs",  externSrc));
  }

  protected Externs externs(String[] srcTexts) {
    return new Externs(createSources("externs", srcTexts));
  }

  protected Externs externs(List<SourceFile> files) {
    return new Externs(files);
  }

  protected WarningDiagnostic warning(DiagnosticType type) {
    return warning(type, null);
  }

  protected WarningDiagnostic warning(DiagnosticType type, String match) {
    // TODO(johnlenz): change this to reject null
    return type != null ? new WarningDiagnostic(type, match) : null;
  }

  protected ErrorDiagnostic error(DiagnosticType type) {
    return error(type, null);
  }

  protected ErrorDiagnostic error(DiagnosticType type, String match) {
    // TODO(johnlenz): change this to reject null
    return type != null ? new ErrorDiagnostic(type, match) : null;
  }

  protected void testSame(TestPart ...parts) {
    Expected expected = null;

    int i = 0;
    TestPart[] finalParts = new TestPart[parts.length + 1];
    for (TestPart part : parts) {
      finalParts[i++] = part;
      if (part instanceof Sources) {
        Preconditions.checkState(expected == null);
        expected = expected(((Sources) part).sources);
      }
    }
    Preconditions.checkState(expected != null);
    finalParts[i++] = expected;

    test(finalParts);
  }

  protected void test(TestPart ...parts) {
    // TODO(johnlenz): make "ignore" and "nothing" explicit.
    Externs externs = null;
    Sources srcs = null;
    Expected expected = null;
    Diagnostic diagnostic = null;
    for (TestPart part : parts) {
      if (part instanceof Externs) {
        Preconditions.checkState(externs == null);
        externs = (Externs) part;
      } else if (part instanceof Sources) {
        Preconditions.checkState(srcs == null);
        srcs = (Sources) part;
      } else if (part instanceof Expected) {
        Preconditions.checkState(expected == null);
        expected = (Expected) part;
      } else if (part instanceof Diagnostic) {
        Preconditions.checkState(diagnostic == null);
        diagnostic = (Diagnostic) part;
      } else {
        throw new IllegalStateException("unexepected " + part.getClass().getName());
      }
    }
    if (externs == null) {
      externs = externs(externsInputs);
    }
    testInternal(externs, srcs, expected, diagnostic);
  }

  // TODO(johnlenz): make this a abstract class with a private constructor
  protected interface TestPart {

  }

  static final class Expected implements TestPart {
    final List<SourceFile> expected;

    Expected(List<SourceFile> files) {
      this.expected = files;
    }
  }

  static final class Sources implements TestPart {
    final List<SourceFile> sources;

    Sources(List<SourceFile> files) {
      sources = files;
    }
  }

  static final class Externs implements TestPart {
    final List<SourceFile> externs;

    Externs(List<SourceFile> files) {
      externs = files;
      if (files != null) {
        for (SourceFile s : files) {
          s.setIsExtern(true);
        }
      }
    }
  }

  static class Diagnostic implements TestPart {
    final CheckLevel level;
    final DiagnosticType diagnostic;
    final String match;

    Diagnostic(CheckLevel level, DiagnosticType diagnostic, String match) {
      this.level = level;
      this.diagnostic = diagnostic;
      this.match = match;
    }
  }

  static class ErrorDiagnostic extends Diagnostic {
    ErrorDiagnostic(DiagnosticType diagnostic, String match) {
      super(CheckLevel.ERROR, diagnostic, match);
    }
  }

  static class WarningDiagnostic extends Diagnostic {
    WarningDiagnostic(DiagnosticType diagnostic, String match) {
      super(CheckLevel.WARNING, diagnostic, match);
    }
  }
}
