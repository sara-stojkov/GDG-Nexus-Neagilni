import torch
import torch.nn as nn


class AllergyModel(nn.Module):
    """
    Linear sensitivity model + logistic reaction mapping.

    E_t = Σ s[i] * x[i,t]
    P(reaction) = sigmoid(k * (E_t - T))
    """

    def __init__(self, n_features: int):
        super().__init__()

        # Sensitivities (learned weights)
        # softplus ensures positivity (biologically meaningful)
        self.s = nn.Parameter(torch.randn(n_features) * 0.1)

        # Threshold: how much exposure is needed for reaction
        self.T = nn.Parameter(torch.tensor(0.0))

        # Steepness: how sharp the reaction boundary is
        self.k = nn.Parameter(torch.tensor(1.0))

    def exposure(self, x: torch.Tensor) -> torch.Tensor:
        """
        Compute E_t = Σ s[i] * x[i,t]
        x: [batch, features] or [features]
        """
        s_pos = torch.nn.functional.softplus(self.s)
        return torch.sum(x * s_pos, dim=-1)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        """
        Returns probability of reaction.
        """
        E = self.exposure(x)
        logits = self.k * (E - self.T)
        return torch.sigmoid(logits)

    def predict_exposure(self, x: torch.Tensor):
        """
        Optional helper for debugging / interpretability.
        """
        return self.exposure(x)
