/** One labelled input in the auth card style (AuthLayout.css), with an optional grey hint under it. */
function AuthField({ id, label, type = "text", value, onChange, hint, autoComplete, autoFocus, maxLength = 100 }) {
  return (
    <div className="form-group">
      <label className="form-label" htmlFor={id}>{label}</label>
      <input
        id={id}
        type={type}
        className="form-input no-prefix"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        autoComplete={autoComplete}
        autoFocus={autoFocus}
        maxLength={maxLength}
        required
      />
      {hint && <p className="auth-field-hint">{hint}</p>}
    </div>
  );
}

export default AuthField;
