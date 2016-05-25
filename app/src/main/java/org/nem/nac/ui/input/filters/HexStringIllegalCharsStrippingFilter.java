package org.nem.nac.ui.input.filters;

import android.text.InputFilter;
import android.text.Spanned;

import org.nem.nac.application.AppConstants;

public class HexStringIllegalCharsStrippingFilter implements InputFilter {
	@Override
	public CharSequence filter(final CharSequence source, final int start, final int end, final Spanned dest, final int dstart, final int dend) {
		return source.toString().replaceAll(AppConstants.REGEX_HEX_INPUT_STRIPPABLE_CHARACTERS, "");
	}
}
