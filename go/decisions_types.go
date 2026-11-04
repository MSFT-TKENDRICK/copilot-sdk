// Request and response types for the experimental Decisions API (session.Decisions()).
//
// This file mirrors nodejs/src/decisions.ts. Request types serialize to the snake_case wire
// format through MarshalJSON; nil pointers and empty enum strings are omitted from the wire.

package copilot

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
)

// marshalDecisionsWire encodes v as compact JSON without HTML escaping.
func marshalDecisionsWire(v any) ([]byte, error) {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return nil, err
	}
	return bytes.TrimSuffix(buf.Bytes(), []byte("\n")), nil
}

// decisionsContent returns parts when non-nil, otherwise the text form.
func decisionsContent[T any](text string, parts []T) any {
	if parts != nil {
		return parts
	}
	return text
}

// ============================================================================
// Content parts
// ============================================================================

// DecisionsCacheBreakpointMode is the mode of a prompt-cache breakpoint.
//
// Experimental: DecisionsCacheBreakpointMode is part of the experimental Decisions API and
// may change or be removed.
type DecisionsCacheBreakpointMode string

const (
	// DecisionsCacheBreakpointModeExplicit marks the end of an explicit prompt-cache segment.
	DecisionsCacheBreakpointModeExplicit DecisionsCacheBreakpointMode = "explicit"
)

// DecisionsCacheBreakpoint marks the end of an explicit prompt-cache segment. An empty Mode
// is sent as "explicit".
//
// Experimental: DecisionsCacheBreakpoint is part of the experimental Decisions API and may
// change or be removed.
type DecisionsCacheBreakpoint struct {
	Mode DecisionsCacheBreakpointMode
}

// MarshalJSON implements [json.Marshaler].
func (b DecisionsCacheBreakpoint) MarshalJSON() ([]byte, error) {
	mode := b.Mode
	if mode == "" {
		mode = DecisionsCacheBreakpointModeExplicit
	}
	return marshalDecisionsWire(map[string]any{"mode": mode})
}

// DecisionsImageDetail is the detail level of an image part. Empty omits the field.
//
// Experimental: DecisionsImageDetail is part of the experimental Decisions API and may
// change or be removed.
type DecisionsImageDetail string

const (
	DecisionsImageDetailLow      DecisionsImageDetail = "low"
	DecisionsImageDetailHigh     DecisionsImageDetail = "high"
	DecisionsImageDetailAuto     DecisionsImageDetail = "auto"
	DecisionsImageDetailOriginal DecisionsImageDetail = "original"
)

// DecisionsUserContentPart is a content part allowed in a user message:
// [DecisionsInputText], [DecisionsInputImage], or [DecisionsInputJSON].
//
// Experimental: DecisionsUserContentPart is part of the experimental Decisions API and may
// change or be removed.
type DecisionsUserContentPart interface {
	decisionsUserContentPart()
}

// DecisionsAssistantContentPart is a content part allowed in an assistant message:
// [DecisionsOutputText] or [DecisionsRefusalContent].
//
// Experimental: DecisionsAssistantContentPart is part of the experimental Decisions API and
// may change or be removed.
type DecisionsAssistantContentPart interface {
	decisionsAssistantContentPart()
}

// DecisionsToolOutputPart is a content part allowed in a tool call output:
// [DecisionsInputText] or [DecisionsInputImage].
//
// Experimental: DecisionsToolOutputPart is part of the experimental Decisions API and may
// change or be removed.
type DecisionsToolOutputPart interface {
	decisionsToolOutputPart()
}

// DecisionsQuestionPart is a content part allowed in question instructions (no cache
// breakpoints): [DecisionsQuestionText], [DecisionsQuestionImage], or [DecisionsInputJSON].
//
// Experimental: DecisionsQuestionPart is part of the experimental Decisions API and may
// change or be removed.
type DecisionsQuestionPart interface {
	decisionsQuestionPart()
}

// DecisionsInputText is a text content part.
//
// Experimental: DecisionsInputText is part of the experimental Decisions API and may change
// or be removed.
type DecisionsInputText struct {
	Text                  string
	PromptCacheBreakpoint *DecisionsCacheBreakpoint
}

func (DecisionsInputText) decisionsUserContentPart() {}
func (DecisionsInputText) decisionsToolOutputPart()  {}

// MarshalJSON implements [json.Marshaler].
func (p DecisionsInputText) MarshalJSON() ([]byte, error) {
	return marshalDecisionsWire(struct {
		Type                  string                    `json:"type"`
		Text                  string                    `json:"text"`
		PromptCacheBreakpoint *DecisionsCacheBreakpoint `json:"prompt_cache_breakpoint,omitempty"`
	}{"input_text", p.Text, p.PromptCacheBreakpoint})
}

// DecisionsInputImage is an image content part. ImageURL is a data URL or an http(s) URL.
//
// Experimental: DecisionsInputImage is part of the experimental Decisions API and may
// change or be removed.
type DecisionsInputImage struct {
	ImageURL              string
	Detail                DecisionsImageDetail
	PromptCacheBreakpoint *DecisionsCacheBreakpoint
}

func (DecisionsInputImage) decisionsUserContentPart() {}
func (DecisionsInputImage) decisionsToolOutputPart()  {}

// MarshalJSON implements [json.Marshaler].
func (p DecisionsInputImage) MarshalJSON() ([]byte, error) {
	return marshalDecisionsWire(struct {
		Type                  string                    `json:"type"`
		ImageURL              string                    `json:"image_url"`
		Detail                DecisionsImageDetail      `json:"detail,omitempty"`
		PromptCacheBreakpoint *DecisionsCacheBreakpoint `json:"prompt_cache_breakpoint,omitempty"`
	}{"input_image", p.ImageURL, p.Detail, p.PromptCacheBreakpoint})
}

// DecisionsInputJSON is a structured JSON content part. Data must encode to a JSON object or
// an array of objects.
//
// Experimental: DecisionsInputJSON is part of the experimental Decisions API and may change
// or be removed.
type DecisionsInputJSON struct {
	Data any
}

func (DecisionsInputJSON) decisionsUserContentPart() {}
func (DecisionsInputJSON) decisionsQuestionPart()    {}

// MarshalJSON implements [json.Marshaler].
func (p DecisionsInputJSON) MarshalJSON() ([]byte, error) {
	return marshalDecisionsWire(struct {
		Type string `json:"type"`
		Data any    `json:"data"`
	}{"input_json", p.Data})
}

// DecisionsOutputText is an assistant text content part.
//
// Experimental: DecisionsOutputText is part of the experimental Decisions API and may change
// or be removed.
type DecisionsOutputText struct {
	Text string
}

func (DecisionsOutputText) decisionsAssistantContentPart() {}

// MarshalJSON implements [json.Marshaler].
func (p DecisionsOutputText) MarshalJSON() ([]byte, error) {
	return marshalDecisionsWire(struct {
		Type string `json:"type"`
		Text string `json:"text"`
	}{"output_text", p.Text})
}

// DecisionsRefusalContent is an assistant refusal content part.
//
// Experimental: DecisionsRefusalContent is part of the experimental Decisions API and may
// change or be removed.
type DecisionsRefusalContent struct {
	Refusal string
}

func (DecisionsRefusalContent) decisionsAssistantContentPart() {}

// MarshalJSON implements [json.Marshaler].
func (p DecisionsRefusalContent) MarshalJSON() ([]byte, error) {
	return marshalDecisionsWire(struct {
		Type    string `json:"type"`
		Refusal string `json:"refusal"`
	}{"refusal", p.Refusal})
}

// DecisionsQuestionText is a text part in question instructions.
//
// Experimental: DecisionsQuestionText is part of the experimental Decisions API and may
// change or be removed.
type DecisionsQuestionText struct {
	Text string
}

func (DecisionsQuestionText) decisionsQuestionPart() {}

// MarshalJSON implements [json.Marshaler].
func (p DecisionsQuestionText) MarshalJSON() ([]byte, error) {
	return marshalDecisionsWire(struct {
		Type string `json:"type"`
		Text string `json:"text"`
	}{"input_text", p.Text})
}

// DecisionsQuestionImage is an image part in question instructions.
//
// Experimental: DecisionsQuestionImage is part of the experimental Decisions API and may
// change or be removed.
type DecisionsQuestionImage struct {
	ImageURL string
	Detail   DecisionsImageDetail
}

func (DecisionsQuestionImage) decisionsQuestionPart() {}

// MarshalJSON implements [json.Marshaler].
func (p DecisionsQuestionImage) MarshalJSON() ([]byte, error) {
	return marshalDecisionsWire(struct {
		Type     string               `json:"type"`
		ImageURL string               `json:"image_url"`
		Detail   DecisionsImageDetail `json:"detail,omitempty"`
	}{"input_image", p.ImageURL, p.Detail})
}

// ============================================================================
// Input items
// ============================================================================

// DecisionsItemStatus is the status of a conversation item. Empty omits the field.
//
// Experimental: DecisionsItemStatus is part of the experimental Decisions API and may change
// or be removed.
type DecisionsItemStatus string

const (
	DecisionsItemStatusInProgress DecisionsItemStatus = "in_progress"
	DecisionsItemStatusCompleted  DecisionsItemStatus = "completed"
	DecisionsItemStatusIncomplete DecisionsItemStatus = "incomplete"
)

// DecisionsAssistantPhase is the phase of an assistant message. Empty omits the field.
//
// Experimental: DecisionsAssistantPhase is part of the experimental Decisions API and may
// change or be removed.
type DecisionsAssistantPhase string

const (
	DecisionsAssistantPhaseCommentary  DecisionsAssistantPhase = "commentary"
	DecisionsAssistantPhaseFinalAnswer DecisionsAssistantPhase = "final_answer"
)

// DecisionsDeveloperRole is the role of a [DecisionsDeveloperMessage].
//
// Experimental: DecisionsDeveloperRole is part of the experimental Decisions API and may
// change or be removed.
type DecisionsDeveloperRole string

const (
	DecisionsDeveloperRoleDeveloper DecisionsDeveloperRole = "developer"
	DecisionsDeveloperRoleSystem    DecisionsDeveloperRole = "system"
)

// DecisionsInputItem is one item of [DecisionsRequest.InputItems]: [DecisionsUserMessage],
// [DecisionsDeveloperMessage], [DecisionsAssistantMessage], [DecisionsFunctionCall],
// [DecisionsFunctionCallOutput], [DecisionsCustomToolCall], [DecisionsCustomToolCallOutput],
// or [DecisionsRawInputItem].
//
// Experimental: DecisionsInputItem is part of the experimental Decisions API and may change
// or be removed.
type DecisionsInputItem interface {
	decisionsInputItem()
}

// DecisionsUserMessage is a user message. When ContentParts is non-nil it is sent instead of
// Content. IncludeType adds "type": "message" to the wire item (the API accepts either form).
//
// Experimental: DecisionsUserMessage is part of the experimental Decisions API and may change
// or be removed.
type DecisionsUserMessage struct {
	Content      string
	ContentParts []DecisionsUserContentPart
	IncludeType  bool
}

func (DecisionsUserMessage) decisionsInputItem() {}

// MarshalJSON implements [json.Marshaler].
func (m DecisionsUserMessage) MarshalJSON() ([]byte, error) {
	return marshalDecisionsMessage(m.IncludeType, "user", decisionsContent(m.Content, m.ContentParts), "")
}

// DecisionsDeveloperMessage is a developer or system message. Role is required. When
// ContentParts is non-nil it is sent instead of Content.
//
// Experimental: DecisionsDeveloperMessage is part of the experimental Decisions API and may
// change or be removed.
type DecisionsDeveloperMessage struct {
	Role         DecisionsDeveloperRole
	Content      string
	ContentParts []DecisionsInputText
	IncludeType  bool
}

func (DecisionsDeveloperMessage) decisionsInputItem() {}

// MarshalJSON implements [json.Marshaler].
func (m DecisionsDeveloperMessage) MarshalJSON() ([]byte, error) {
	if m.Role != DecisionsDeveloperRoleDeveloper && m.Role != DecisionsDeveloperRoleSystem {
		return nil, fmt.Errorf("DecisionsDeveloperMessage.Role must be %q or %q", DecisionsDeveloperRoleDeveloper, DecisionsDeveloperRoleSystem)
	}
	return marshalDecisionsMessage(m.IncludeType, string(m.Role), decisionsContent(m.Content, m.ContentParts), "")
}

// DecisionsAssistantMessage is an assistant message. When ContentParts is non-nil it is sent
// instead of Content.
//
// Experimental: DecisionsAssistantMessage is part of the experimental Decisions API and may
// change or be removed.
type DecisionsAssistantMessage struct {
	Content      string
	ContentParts []DecisionsAssistantContentPart
	Phase        DecisionsAssistantPhase
	IncludeType  bool
}

func (DecisionsAssistantMessage) decisionsInputItem() {}

// MarshalJSON implements [json.Marshaler].
func (m DecisionsAssistantMessage) MarshalJSON() ([]byte, error) {
	return marshalDecisionsMessage(m.IncludeType, "assistant", decisionsContent(m.Content, m.ContentParts), m.Phase)
}

func marshalDecisionsMessage(includeType bool, role string, content any, phase DecisionsAssistantPhase) ([]byte, error) {
	wire := struct {
		Type    string                  `json:"type,omitempty"`
		Role    string                  `json:"role"`
		Content any                     `json:"content"`
		Phase   DecisionsAssistantPhase `json:"phase,omitempty"`
	}{Role: role, Content: content, Phase: phase}
	if includeType {
		wire.Type = "message"
	}
	return marshalDecisionsWire(wire)
}

// DecisionsFunctionCall is a function call made by the model.
//
// Experimental: DecisionsFunctionCall is part of the experimental Decisions API and may
// change or be removed.
type DecisionsFunctionCall struct {
	CallID    string
	Name      string
	Arguments string
	ID        *string
	Namespace *string
	Status    DecisionsItemStatus
}

func (DecisionsFunctionCall) decisionsInputItem() {}

// MarshalJSON implements [json.Marshaler].
func (c DecisionsFunctionCall) MarshalJSON() ([]byte, error) {
	return marshalDecisionsWire(struct {
		Type      string              `json:"type"`
		CallID    string              `json:"call_id"`
		Name      string              `json:"name"`
		Arguments string              `json:"arguments"`
		ID        *string             `json:"id,omitempty"`
		Namespace *string             `json:"namespace,omitempty"`
		Status    DecisionsItemStatus `json:"status,omitempty"`
	}{"function_call", c.CallID, c.Name, c.Arguments, c.ID, c.Namespace, c.Status})
}

// DecisionsFunctionCallOutput is the output of a function call. When OutputParts is non-nil
// it is sent instead of Output.
//
// Experimental: DecisionsFunctionCallOutput is part of the experimental Decisions API and may
// change or be removed.
type DecisionsFunctionCallOutput struct {
	CallID      string
	Output      string
	OutputParts []DecisionsToolOutputPart
	ID          *string
	Status      DecisionsItemStatus
}

func (DecisionsFunctionCallOutput) decisionsInputItem() {}

// MarshalJSON implements [json.Marshaler].
func (o DecisionsFunctionCallOutput) MarshalJSON() ([]byte, error) {
	return marshalDecisionsToolOutput("function_call_output", o.CallID, decisionsContent(o.Output, o.OutputParts), o.ID, o.Status)
}

// DecisionsCustomToolCall is a custom tool call made by the model.
//
// Experimental: DecisionsCustomToolCall is part of the experimental Decisions API and may
// change or be removed.
type DecisionsCustomToolCall struct {
	CallID    string
	Name      string
	Input     string
	ID        *string
	Namespace *string
	Status    DecisionsItemStatus
}

func (DecisionsCustomToolCall) decisionsInputItem() {}

// MarshalJSON implements [json.Marshaler].
func (c DecisionsCustomToolCall) MarshalJSON() ([]byte, error) {
	return marshalDecisionsWire(struct {
		Type      string              `json:"type"`
		CallID    string              `json:"call_id"`
		Name      string              `json:"name"`
		Input     string              `json:"input"`
		ID        *string             `json:"id,omitempty"`
		Namespace *string             `json:"namespace,omitempty"`
		Status    DecisionsItemStatus `json:"status,omitempty"`
	}{"custom_tool_call", c.CallID, c.Name, c.Input, c.ID, c.Namespace, c.Status})
}

// DecisionsCustomToolCallOutput is the output of a custom tool call. When OutputParts is
// non-nil it is sent instead of Output.
//
// Experimental: DecisionsCustomToolCallOutput is part of the experimental Decisions API and
// may change or be removed.
type DecisionsCustomToolCallOutput struct {
	CallID      string
	Output      string
	OutputParts []DecisionsToolOutputPart
	ID          *string
	Status      DecisionsItemStatus
}

func (DecisionsCustomToolCallOutput) decisionsInputItem() {}

// MarshalJSON implements [json.Marshaler].
func (o DecisionsCustomToolCallOutput) MarshalJSON() ([]byte, error) {
	return marshalDecisionsToolOutput("custom_tool_call_output", o.CallID, decisionsContent(o.Output, o.OutputParts), o.ID, o.Status)
}

func marshalDecisionsToolOutput(itemType, callID string, output any, id *string, status DecisionsItemStatus) ([]byte, error) {
	return marshalDecisionsWire(struct {
		Type   string              `json:"type"`
		CallID string              `json:"call_id"`
		Output any                 `json:"output"`
		ID     *string             `json:"id,omitempty"`
		Status DecisionsItemStatus `json:"status,omitempty"`
	}{itemType, callID, output, id, status})
}

// DecisionsRawInputItem is an input item sent verbatim (already in wire format). Use it for
// item types this SDK version does not model yet. Raw must be a JSON object.
//
// Experimental: DecisionsRawInputItem is part of the experimental Decisions API and may
// change or be removed.
type DecisionsRawInputItem struct {
	Raw json.RawMessage
}

func (DecisionsRawInputItem) decisionsInputItem() {}

// MarshalJSON implements [json.Marshaler].
func (r DecisionsRawInputItem) MarshalJSON() ([]byte, error) {
	trimmed := bytes.TrimLeft(r.Raw, " \t\r\n")
	if len(trimmed) == 0 || trimmed[0] != '{' || !json.Valid(r.Raw) {
		return nil, errors.New("DecisionsRawInputItem.Raw must be a JSON object")
	}
	return r.Raw, nil
}

// ============================================================================
// Questions
// ============================================================================

// DecisionsChoiceValue is a choice value: a string or a boolean. The distinction is
// preserved on the wire and in responses. The zero value is the empty string.
//
// Experimental: DecisionsChoiceValue is part of the experimental Decisions API and may change
// or be removed.
type DecisionsChoiceValue struct {
	str    string
	b      bool
	isBool bool
}

// DecisionsChoiceString returns a string choice value.
//
// Experimental: DecisionsChoiceString is part of the experimental Decisions API and may
// change or be removed.
func DecisionsChoiceString(value string) DecisionsChoiceValue {
	return DecisionsChoiceValue{str: value}
}

// DecisionsChoiceBool returns a boolean choice value.
//
// Experimental: DecisionsChoiceBool is part of the experimental Decisions API and may change
// or be removed.
func DecisionsChoiceBool(value bool) DecisionsChoiceValue {
	return DecisionsChoiceValue{b: value, isBool: true}
}

// IsBool reports whether the value is a boolean.
func (v DecisionsChoiceValue) IsBool() bool { return v.isBool }

// BoolValue returns the boolean value (false for string values).
func (v DecisionsChoiceValue) BoolValue() bool { return v.b }

// StringValue returns the string value ("" for boolean values).
func (v DecisionsChoiceValue) StringValue() string { return v.str }

// Value returns the value as a string or a bool.
func (v DecisionsChoiceValue) Value() any {
	if v.isBool {
		return v.b
	}
	return v.str
}

// MarshalJSON implements [json.Marshaler].
func (v DecisionsChoiceValue) MarshalJSON() ([]byte, error) {
	return marshalDecisionsWire(v.Value())
}

// UnmarshalJSON implements [json.Unmarshaler]. Only JSON strings and booleans are accepted.
func (v *DecisionsChoiceValue) UnmarshalJSON(data []byte) error {
	var raw any
	if err := json.Unmarshal(data, &raw); err != nil {
		return err
	}
	switch value := raw.(type) {
	case string:
		*v = DecisionsChoiceString(value)
	case bool:
		*v = DecisionsChoiceBool(value)
	default:
		return errors.New("choice value must be a string or a boolean")
	}
	return nil
}

// DecisionsChoiceOption is one option of a [DecisionsChoiceQuestion].
//
// Experimental: DecisionsChoiceOption is part of the experimental Decisions API and may
// change or be removed.
type DecisionsChoiceOption struct {
	Value       DecisionsChoiceValue `json:"value"`
	Description *string              `json:"description,omitempty"`
}

// DecisionsScoreLevel is one level of a [DecisionsScoreQuestion].
//
// Experimental: DecisionsScoreLevel is part of the experimental Decisions API and may change
// or be removed.
type DecisionsScoreLevel struct {
	Label       string  `json:"label"`
	Description *string `json:"description,omitempty"`
}

// DecisionsQuestion is one question of a request: [DecisionsPredicateQuestion],
// [DecisionsChoiceQuestion], or [DecisionsScoreQuestion].
//
// Experimental: DecisionsQuestion is part of the experimental Decisions API and may change
// or be removed.
type DecisionsQuestion interface {
	decisionsQuestion()
}

// DecisionsPredicateQuestion asks for the probability that a statement holds. When
// InstructionParts is non-nil it is sent instead of Instructions.
//
// Experimental: DecisionsPredicateQuestion is part of the experimental Decisions API and may
// change or be removed.
type DecisionsPredicateQuestion struct {
	Name             *string
	Instructions     string
	InstructionParts []DecisionsQuestionPart
}

func (DecisionsPredicateQuestion) decisionsQuestion() {}

// MarshalJSON implements [json.Marshaler].
func (q DecisionsPredicateQuestion) MarshalJSON() ([]byte, error) {
	return marshalDecisionsWire(struct {
		Type         string  `json:"type"`
		Name         *string `json:"name,omitempty"`
		Instructions any     `json:"instructions"`
	}{"predicate", q.Name, decisionsContent(q.Instructions, q.InstructionParts)})
}

// DecisionsChoiceQuestion asks the model to pick one of Choices. When InstructionParts is
// non-nil it is sent instead of Instructions.
//
// Experimental: DecisionsChoiceQuestion is part of the experimental Decisions API and may
// change or be removed.
type DecisionsChoiceQuestion struct {
	Name             *string
	Instructions     string
	InstructionParts []DecisionsQuestionPart
	Choices          []DecisionsChoiceOption
}

func (DecisionsChoiceQuestion) decisionsQuestion() {}

// MarshalJSON implements [json.Marshaler].
func (q DecisionsChoiceQuestion) MarshalJSON() ([]byte, error) {
	choices := q.Choices
	if choices == nil {
		choices = []DecisionsChoiceOption{}
	}
	return marshalDecisionsWire(struct {
		Type         string                  `json:"type"`
		Name         *string                 `json:"name,omitempty"`
		Instructions any                     `json:"instructions"`
		Choices      []DecisionsChoiceOption `json:"choices"`
	}{"choice", q.Name, decisionsContent(q.Instructions, q.InstructionParts), choices})
}

// DecisionsScoreQuestion asks the model to grade on the ordered Levels. When
// InstructionParts is non-nil it is sent instead of Instructions.
//
// Experimental: DecisionsScoreQuestion is part of the experimental Decisions API and may
// change or be removed.
type DecisionsScoreQuestion struct {
	Name             *string
	Instructions     string
	InstructionParts []DecisionsQuestionPart
	Levels           []DecisionsScoreLevel
}

func (DecisionsScoreQuestion) decisionsQuestion() {}

// MarshalJSON implements [json.Marshaler].
func (q DecisionsScoreQuestion) MarshalJSON() ([]byte, error) {
	levels := q.Levels
	if levels == nil {
		levels = []DecisionsScoreLevel{}
	}
	return marshalDecisionsWire(struct {
		Type         string                `json:"type"`
		Name         *string               `json:"name,omitempty"`
		Instructions any                   `json:"instructions"`
		Levels       []DecisionsScoreLevel `json:"levels"`
	}{"score", q.Name, decisionsContent(q.Instructions, q.InstructionParts), levels})
}

// ============================================================================
// Request
// ============================================================================

// DecisionsPromptCacheMode is the prompt-cache mode. Empty omits the field.
//
// Experimental: DecisionsPromptCacheMode is part of the experimental Decisions API and may
// change or be removed.
type DecisionsPromptCacheMode string

const (
	DecisionsPromptCacheModeImplicit DecisionsPromptCacheMode = "implicit"
	DecisionsPromptCacheModeExplicit DecisionsPromptCacheMode = "explicit"
)

// DecisionsPromptCacheTTL is the prompt-cache time to live. Empty omits the field.
//
// Experimental: DecisionsPromptCacheTTL is part of the experimental Decisions API and may
// change or be removed.
type DecisionsPromptCacheTTL string

const (
	DecisionsPromptCacheTTL30m DecisionsPromptCacheTTL = "30m"
)

// DecisionsPromptCacheOptions configures prompt caching.
//
// Experimental: DecisionsPromptCacheOptions is part of the experimental Decisions API and may
// change or be removed.
type DecisionsPromptCacheOptions struct {
	Mode DecisionsPromptCacheMode `json:"mode,omitempty"`
	TTL  DecisionsPromptCacheTTL  `json:"ttl,omitempty"`
}

// DecisionsRequest is a Decisions API request. Nil pointers and empty enum strings are
// omitted from the wire request.
//
// Experimental: DecisionsRequest is part of the experimental Decisions API and may change or
// be removed.
type DecisionsRequest struct {
	// Model to send on the wire (for Azure, the deployment name). Required.
	Model string
	// Input is the plain-text input, used when InputItems is nil.
	Input string
	// InputItems is the structured input. When non-nil it is sent instead of Input.
	InputItems []DecisionsInputItem
	// Questions holds 1 to 400 questions (not validated client-side).
	Questions          []DecisionsQuestion
	PromptCacheKey     *string
	PromptCacheOptions *DecisionsPromptCacheOptions
	// SafetyIdentifier is an opaque, stable identifier for the end user, used for abuse
	// detection. Never send an email address, username, or credential; hash or map it first.
	SafetyIdentifier *string
}

var errDecisionsModelRequired = errors.New("DecisionsRequest.Model is required")

// MarshalJSON implements [json.Marshaler], producing the wire request.
func (r DecisionsRequest) MarshalJSON() ([]byte, error) {
	if r.Model == "" {
		return nil, errDecisionsModelRequired
	}
	questions := r.Questions
	if questions == nil {
		questions = []DecisionsQuestion{}
	}
	return marshalDecisionsWire(struct {
		Model              string                       `json:"model"`
		Input              any                          `json:"input"`
		Questions          []DecisionsQuestion          `json:"questions"`
		PromptCacheKey     *string                      `json:"prompt_cache_key,omitempty"`
		PromptCacheOptions *DecisionsPromptCacheOptions `json:"prompt_cache_options,omitempty"`
		SafetyIdentifier   *string                      `json:"safety_identifier,omitempty"`
	}{r.Model, decisionsContent(r.Input, r.InputItems), questions, r.PromptCacheKey, r.PromptCacheOptions, r.SafetyIdentifier})
}

// ============================================================================
// Response
// ============================================================================

// DecisionsAnswerType identifies the variant of a [DecisionsAnswer].
//
// Experimental: DecisionsAnswerType is part of the experimental Decisions API and may change
// or be removed.
type DecisionsAnswerType string

const (
	DecisionsAnswerTypePredicate DecisionsAnswerType = "predicate"
	DecisionsAnswerTypeChoice    DecisionsAnswerType = "choice"
	DecisionsAnswerTypeScore     DecisionsAnswerType = "score"
	DecisionsAnswerTypeRefusal   DecisionsAnswerType = "refusal"
	// DecisionsAnswerTypeUnknown is SDK-owned: the wire type was not recognized.
	DecisionsAnswerTypeUnknown DecisionsAnswerType = "unknown"
)

// DecisionsAnswer is one answer of a [DecisionsResult]: [DecisionsPredicateAnswer],
// [DecisionsChoiceAnswer], [DecisionsScoreAnswer], [DecisionsRefusalAnswer], or
// [DecisionsUnknownAnswer]. Use a type switch to inspect it.
//
// Experimental: DecisionsAnswer is part of the experimental Decisions API and may change or
// be removed.
type DecisionsAnswer interface {
	decisionsAnswer()
	Type() DecisionsAnswerType
	// AnswerName returns the question name, or nil when the question was unnamed.
	AnswerName() *string
}

// DecisionsPredicateAnswer answers a predicate question.
//
// Experimental: DecisionsPredicateAnswer is part of the experimental Decisions API and may
// change or be removed.
type DecisionsPredicateAnswer struct {
	Name        *string
	Probability float64
}

func (DecisionsPredicateAnswer) decisionsAnswer() {}

// Type implements [DecisionsAnswer].
func (DecisionsPredicateAnswer) Type() DecisionsAnswerType { return DecisionsAnswerTypePredicate }

// AnswerName implements [DecisionsAnswer].
func (a DecisionsPredicateAnswer) AnswerName() *string { return a.Name }

// DecisionsChoiceProbability is the probability of one choice value.
//
// Experimental: DecisionsChoiceProbability is part of the experimental Decisions API and may
// change or be removed.
type DecisionsChoiceProbability struct {
	Value       DecisionsChoiceValue
	Probability float64
}

// DecisionsChoiceAnswer answers a choice question.
//
// Experimental: DecisionsChoiceAnswer is part of the experimental Decisions API and may
// change or be removed.
type DecisionsChoiceAnswer struct {
	Name          *string
	Choice        DecisionsChoiceValue
	Confidence    float64
	Probabilities []DecisionsChoiceProbability
}

func (DecisionsChoiceAnswer) decisionsAnswer() {}

// Type implements [DecisionsAnswer].
func (DecisionsChoiceAnswer) Type() DecisionsAnswerType { return DecisionsAnswerTypeChoice }

// AnswerName implements [DecisionsAnswer].
func (a DecisionsChoiceAnswer) AnswerName() *string { return a.Name }

// DecisionsScoreProbability is the probability of one score level.
//
// Experimental: DecisionsScoreProbability is part of the experimental Decisions API and may
// change or be removed.
type DecisionsScoreProbability struct {
	Label string
	// Value is the integer index of the level.
	Value       int
	Probability float64
}

// DecisionsScoreAnswer answers a score question.
//
// Experimental: DecisionsScoreAnswer is part of the experimental Decisions API and may change
// or be removed.
type DecisionsScoreAnswer struct {
	Name *string
	// Score is the probability-weighted level index (fractional).
	Score         float64
	Confidence    float64
	Probabilities []DecisionsScoreProbability
}

func (DecisionsScoreAnswer) decisionsAnswer() {}

// Type implements [DecisionsAnswer].
func (DecisionsScoreAnswer) Type() DecisionsAnswerType { return DecisionsAnswerTypeScore }

// AnswerName implements [DecisionsAnswer].
func (a DecisionsScoreAnswer) AnswerName() *string { return a.Name }

// DecisionsRefusalAnswer reports that the model refused to answer.
//
// Experimental: DecisionsRefusalAnswer is part of the experimental Decisions API and may
// change or be removed.
type DecisionsRefusalAnswer struct {
	Name *string
}

func (DecisionsRefusalAnswer) decisionsAnswer() {}

// Type implements [DecisionsAnswer].
func (DecisionsRefusalAnswer) Type() DecisionsAnswerType { return DecisionsAnswerTypeRefusal }

// AnswerName implements [DecisionsAnswer].
func (a DecisionsRefusalAnswer) AnswerName() *string { return a.Name }

// DecisionsUnknownAnswer is an answer whose type this SDK version does not recognize.
// WireType is the original "type" and Raw is the original JSON object.
//
// Experimental: DecisionsUnknownAnswer is part of the experimental Decisions API and may
// change or be removed.
type DecisionsUnknownAnswer struct {
	Name     *string
	WireType string
	Raw      json.RawMessage
}

func (DecisionsUnknownAnswer) decisionsAnswer() {}

// Type implements [DecisionsAnswer].
func (DecisionsUnknownAnswer) Type() DecisionsAnswerType { return DecisionsAnswerTypeUnknown }

// AnswerName implements [DecisionsAnswer].
func (a DecisionsUnknownAnswer) AnswerName() *string { return a.Name }

// DecisionsInputTokensDetails breaks down input token usage.
//
// Experimental: DecisionsInputTokensDetails is part of the experimental Decisions API and may
// change or be removed.
type DecisionsInputTokensDetails struct {
	CachedTokens     int64
	CacheWriteTokens int64
}

// DecisionsOutputTokensDetails breaks down output token usage.
//
// Experimental: DecisionsOutputTokensDetails is part of the experimental Decisions API and
// may change or be removed.
type DecisionsOutputTokensDetails struct {
	ReasoningTokens int64
}

// DecisionsUsage reports token usage. Missing counts are zero.
//
// Experimental: DecisionsUsage is part of the experimental Decisions API and may change or be
// removed.
type DecisionsUsage struct {
	InputTokens         int64
	InputTokensDetails  DecisionsInputTokensDetails
	OutputTokens        int64
	OutputTokensDetails DecisionsOutputTokensDetails
	TotalTokens         int64
}

// DecisionsResult is a Decisions API response.
//
// Experimental: DecisionsResult is part of the experimental Decisions API and may change or
// be removed.
type DecisionsResult struct {
	// Answers holds one answer per question, in question order.
	Answers []DecisionsAnswer
	Model   string
	Usage   DecisionsUsage
}
