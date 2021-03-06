package com.jakduk.api.common.constraint;

import com.jakduk.api.model.simple.UserProfile;
import com.jakduk.api.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;
import java.util.Objects;

/**
 * @author pyohwan
 * 16. 7. 3 오후 9:30
 */

public class ExistEmailValidator implements ConstraintValidator<ExistEmail, String> {

    @Autowired
    private UserService userService;

    @Override
    public void initialize(ExistEmail constraintAnnotation) {
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {

        Objects.requireNonNull(value);

        UserProfile existEmail = userService.findOneByEmail(value);

        return Objects.isNull(existEmail);
    }

}
